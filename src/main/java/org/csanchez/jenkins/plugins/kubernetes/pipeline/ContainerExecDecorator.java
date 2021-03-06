/*
 * Copyright (C) 2015 Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import static org.csanchez.jenkins.plugins.kubernetes.pipeline.Constants.*;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.output.TeeOutputStream;

import com.google.common.io.NullOutputStream;

import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.Proc;
import hudson.model.Node;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import okhttp3.Response;

/**
 * This decorator interacts directly with the Kubernetes exec API to run commands inside a container. It does not use
 * the Jenkins slave to execute commands.
 *
 */
public class ContainerExecDecorator extends LauncherDecorator implements Serializable, Closeable {

    private static final long serialVersionUID = 4419929753433397655L;
    private static final long DEFAULT_CONTAINER_READY_TIMEOUT = 5;
    private static final String CONTAINER_READY_TIMEOUT_SYSTEM_PROPERTY = ContainerExecDecorator.class.getName() + ".containerReadyTimeout";
    private static final long CONTAINER_READY_TIMEOUT = containerReadyTimeout();
    private static final String COOKIE_VAR = "JENKINS_SERVER_COOKIE";
    private static final Logger LOGGER = Logger.getLogger(ContainerExecDecorator.class.getName());

    private final transient KubernetesClient client;
    private final String podName;
    private final String namespace;
    private final String containerName;

    private transient ExecWatch watch;
    private transient ContainerExecProc proc;

    public ContainerExecDecorator(KubernetesClient client, String podName,  String containerName, String namespace) {
        this.client = client;
        this.podName = podName;
        this.namespace = namespace;
        this.containerName = containerName;
    }

    @Deprecated
    public ContainerExecDecorator(KubernetesClient client, String podName,  String containerName, AtomicBoolean alive, CountDownLatch started, CountDownLatch finished, String namespace) {
        this(client, podName, containerName, namespace);
    }

    @Deprecated
    public ContainerExecDecorator(KubernetesClient client, String podName, String containerName, AtomicBoolean alive, CountDownLatch started, CountDownLatch finished) {
        this(client, podName, containerName, null);
    }

    @Deprecated
    public ContainerExecDecorator(KubernetesClient client, String podName, String containerName, String path, AtomicBoolean alive, CountDownLatch started, CountDownLatch finished) {
        this(client, podName, containerName, null);
    }

    @Override
    public Launcher decorate(final Launcher launcher, final Node node) {
        return new Launcher.DecoratedLauncher(launcher) {
            @Override
            public Proc launch(ProcStarter starter) throws IOException {
                if (!waitUntilContainerIsReady()) {
                    throw new IOException("Failed to execute shell script inside container " +
                            "[" + containerName + "] of pod [" + podName + "]." +
                            " Timed out waiting for container to become ready!");
                }

                final CountDownLatch started = new CountDownLatch(1);
                final CountDownLatch finished = new CountDownLatch(1);
                final AtomicBoolean alive = new AtomicBoolean(false);

                PrintStream printStream = launcher.getListener().getLogger();
                OutputStream stream = printStream;
                // Do not send this command to the output when in quiet mode
                if (starter.quiet()) {
                    stream = new NullOutputStream();
                    printStream = new PrintStream(stream, false, StandardCharsets.UTF_8.toString());
                }
                
                // we need to keep the last bytes in the stream to parse the exit code as it is printed there
                // so we use a buffer
                ExitCodeOutputStream exitCodeOutputStream = new ExitCodeOutputStream();
                // send container output both to the job output and our buffer
                stream = new TeeOutputStream(exitCodeOutputStream, stream);

                String msg = "Executing shell script inside container [" + containerName + "] of pod [" + podName + "]";
                LOGGER.log(Level.FINEST, msg);
                printStream.println(msg);

                watch = client.pods().inNamespace(namespace).withName(podName).inContainer(containerName)
                        .redirectingInput().writingOutput(stream).writingError(stream).withTTY()
                        .usingListener(new ExecListener() {
                            @Override
                            public void onOpen(Response response) {
                                alive.set(true);
                                started.countDown();
                            }

                            @Override
                            public void onFailure(Throwable t, Response response) {
                                alive.set(false);
                                t.printStackTrace(launcher.getListener().getLogger());
                                started.countDown();
                                LOGGER.log(Level.FINEST, "onFailure : {0}", finished);
                                if (finished.getCount() == 0) {
                                    LOGGER.log(Level.WARNING,
                                            "onFailure called but latch already finished. This may be a bug in the kubernetes-plugin");
                                }
                                finished.countDown();
                            }

                            @Override
                            public void onClose(int i, String s) {
                                alive.set(false);
                                started.countDown();
                                LOGGER.log(Level.FINEST, "onClose : {0}", finished);
                                if (finished.getCount() == 0) {
                                    LOGGER.log(Level.SEVERE,
                                            "onClose called but latch already finished. This indicates a bug in the kubernetes-plugin");
                                }
                                finished.countDown();
                            }
                        }).exec();

                waitQuietly(started);

                if (starter.pwd() != null) {
                    // We need to get into the project workspace.
                    // The workspace is not known in advance, so we have to execute a cd command.
                    watch.getInput().write(
                            String.format("cd \"%s\"%s", starter.pwd(), NEWLINE).getBytes(StandardCharsets.UTF_8));
                }
                doExec(watch, printStream, getCommands(starter));
                proc = new ContainerExecProc(watch, alive, finished, new Callable<Integer>() {
                    @Override
                    public Integer call() {
                        return exitCodeOutputStream.getExitCode();
                    }
                });
                return proc;
            }

            @Override
            public void kill(Map<String, String> modelEnvVars) throws IOException, InterruptedException {
                // String cookie = modelEnvVars.get(COOKIE_VAR);
                // TODO we need to use the cookie for something
                getListener().getLogger().println("Killing process.");
                ContainerExecDecorator.this.close();
            }


            private boolean isContainerReady(Pod pod, String container) {
                if (pod == null || pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
                    return false;
                }

                for (ContainerStatus info : pod.getStatus().getContainerStatuses()) {
                    if (info.getName().equals(container) && info.getReady()) {
                        return true;
                    }
                }
                return false;
            }

            private boolean waitUntilContainerIsReady() {
                int i = 0;
                int j = 10; // wait 60 seconds
                Pod pod = client.pods().inNamespace(namespace).withName(podName).get();

                if (pod == null) {
                    launcher.getListener().getLogger().println("Waiting for pod [" + podName + "] to exist.");
                    // wait for Pod to be running.
                    for (; i < j; i++) {
                        LOGGER.log(Level.INFO, "Getting pod ({1}/{2}): {0}", new Object[] {podName, i, j});
                        pod = client.pods().inNamespace(namespace).withName(podName).get();
                        if (pod != null) {
                            break;
                        }
                        LOGGER.log(Level.INFO, "Waiting 6 seconds before checking if pod exists ({1}/{2}): {0}", new Object[] {podName, i, j});
                        try {
                            Thread.sleep(6000);
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                }

                if (pod == null) {
                    throw new IllegalArgumentException("Container with name:[" + containerName + "] not found in pod:[" + podName + "], pod doesn't exist");
                }

                if (isContainerReady(pod, containerName)) {
                    return true;
                }

                launcher.getListener().getLogger().println("Waiting for container container [" + containerName + "] of pod [" + podName + "] to become ready.");
                final CountDownLatch latch = new CountDownLatch(1);
                Watcher<Pod> podWatcher = new Watcher<Pod>() {
                    @Override
                    public void eventReceived(Action action, Pod resource) {
                        switch (action) {
                            case MODIFIED:
                                if (isContainerReady(resource, containerName)) {
                                    latch.countDown();
                                }
                                break;
                            default:
                                break;
                        }
                    }

                    @Override
                    public void onClose(KubernetesClientException cause) {

                    }
                };

                try (Watch watch = client.pods().inNamespace(namespace).withName(podName).watch(podWatcher)) {
                    if (latch.await(CONTAINER_READY_TIMEOUT, TimeUnit.MINUTES)) {
                        return true;
                    }
                } catch (InterruptedException e) {
                    return false;
                }
                return false;
            }
        };
    }

    @Override
    public void close() throws IOException {
        if (watch != null) {
            try {
                watch.close();
            } catch (IllegalStateException e) {
                LOGGER.log(Level.INFO, "Watch was already closed: {0}", e.getMessage());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error closing watch", e);
            } finally {
                watch = null;
            }
        }

        if (proc != null) {
            try {
                proc.kill();
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }
    }

    private static void doExec(ExecWatch watch, PrintStream out, String... statements) {
        try {
            out.print("Executing command: ");
            StringBuilder sb = new StringBuilder();
            for (String stmt : statements) {
                String s = String.format("\"%s\" ", stmt);
                sb.append(s);
                out.print(s);
                watch.getInput().write(s.getBytes(StandardCharsets.UTF_8));
            }
            sb.append(NEWLINE);
            out.println();
            watch.getInput().write(NEWLINE.getBytes(StandardCharsets.UTF_8));

            // get the command exit code and print it padded so it is easier to parse in ConatinerExecProc
            // We need to exit so that we know when the command has finished.
            sb.append(ExitCodeOutputStream.EXIT_COMMAND);
            out.print(ExitCodeOutputStream.EXIT_COMMAND);
            LOGGER.log(Level.FINEST, "Executing command: {0}", sb.toString());
            watch.getInput().write(ExitCodeOutputStream.EXIT_COMMAND.getBytes(StandardCharsets.UTF_8));

            out.flush();
            watch.getInput().flush();
        } catch (IOException e) {
            e.printStackTrace(out);
            throw new RuntimeException(e);
        }
    }

    static String[] getCommands(Launcher.ProcStarter starter) {
        List<String> allCommands = new ArrayList<String>();

        // BourneShellScript.launchWithCookie escapes $ as $$, we convert it to \$
        for (String cmd : starter.cmds()) {
            allCommands.add(cmd.replaceAll("\\$\\$", "\\\\\\$"));
        }
        return allCommands.toArray(new String[allCommands.size()]);
    }

    private static void waitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            //ignore
        }
    }

    private static Long containerReadyTimeout() {
        String timeout = System.getProperty(CONTAINER_READY_TIMEOUT_SYSTEM_PROPERTY, String.valueOf(DEFAULT_CONTAINER_READY_TIMEOUT));
        try {
            return Long.parseLong(timeout);
        } catch (NumberFormatException e) {
            return DEFAULT_CONTAINER_READY_TIMEOUT;
        }
    }

    /**
     * Keeps the last bytes of the output stream to parse the exit code
     */
    static class ExitCodeOutputStream extends OutputStream {

        public static final String EXIT_COMMAND_TXT = "EXITCODE";
        public static final String EXIT_COMMAND = "printf \"" + EXIT_COMMAND_TXT + " %3d\" $?; " + EXIT + NEWLINE;

        private EvictingQueue<Integer> queue = EvictingQueue.create(13);

        public ExitCodeOutputStream() {
            
        }

        @Override
        public void write(int b) throws IOException {
            queue.add(b);
            byte[] bb = new byte[]{(byte) b};
            System.out.print(new String(bb, StandardCharsets.UTF_8));
        }

        public int getExitCode() {
            ByteBuffer b = ByteBuffer.allocate(queue.size());
            queue.stream().filter(Objects::nonNull).forEach((i) -> b.put((byte) i.intValue()));
            // output ends in a 3 digit padded exit code + newline (13 10)
            // as defined in ContainerExecDecorator#doExec
            // ie. 32 32 49 13 10 for exit code 1
            int i = 1;
            String s = new String(b.array(), StandardCharsets.UTF_8);
            if (s.indexOf(EXIT_COMMAND_TXT) < 0) {
                LOGGER.log(Level.WARNING, "Unable to find \"{0}\" in {1}", new Object[] { EXIT_COMMAND_TXT, s });
                return i;
            }
            s = s.substring(s.indexOf(EXIT_COMMAND_TXT) + EXIT_COMMAND_TXT.length(), s.length()).trim();
            try {
                i = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING, "Unable to parse exit code as integer: \"{0}\" {1} / {2}",
                        new Object[] { s, queue.toString(), Arrays.toString(b.array()) });
            }
            return i;
        }
    }
}
