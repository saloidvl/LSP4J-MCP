package com.saloidvl.lsp4jmcp.supervisor;

import com.saloidvl.lsp4jmcp.config.JdtlsSettingsInputs;
import com.saloidvl.lsp4jmcp.runtime.RuntimeConstants;
import com.saloidvl.lsp4jmcp.worker.RepoWorkerMain;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@FunctionalInterface
public interface WorkerProcessLauncher {
    StartedWorker start(
            Path workspacePath,
            String jdtlsCommand,
            JdtlsSettingsInputs settingsInputs) throws Exception;

    record StartedWorker(
            Process process,
            long workerPid,
            String host,
            int port,
            String settingsFingerprint) {
    }

    record WorkerStartup(int port, String settingsFingerprint) {
    }

    final class JvmWorkerProcessLauncher implements WorkerProcessLauncher {
        @Override
        public StartedWorker start(
                Path workspacePath,
                String jdtlsCommand,
                JdtlsSettingsInputs settingsInputs) throws Exception {
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("java.class.path");

            Process process = buildProcessBuilder(
                    workspacePath, jdtlsCommand, settingsInputs, javaBin, classpath).start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            WorkerStartup startup = awaitStartup(
                    process, reader, RuntimeConstants.WORKER_STARTUP_TIMEOUT);
            Thread drain = new Thread(() -> {
                try (reader) {
                    reader.transferTo(Writer.nullWriter());
                } catch (Exception ignored) {
                }
            });
            drain.setDaemon(true);
            drain.start();
            return new StartedWorker(
                    process,
                    process.pid(),
                    "127.0.0.1",
                    startup.port(),
                    startup.settingsFingerprint());
        }

        static ProcessBuilder buildProcessBuilder(
                Path workspacePath,
                String jdtlsCommand,
                JdtlsSettingsInputs settingsInputs,
                String javaBin,
                String classpath) {
            ProcessBuilder builder = new ProcessBuilder(
                    javaBin,
                    "-cp",
                    classpath,
                    RepoWorkerMain.class.getName(),
                    workspacePath.toString(),
                    jdtlsCommand);
            settingsInputs.installInto(builder.environment());
            return builder.redirectErrorStream(true);
        }

        static WorkerStartup parseStartupLine(String line) {
            if (line != null && line.startsWith("ERROR ")) {
                throw new IllegalStateException(line.substring("ERROR ".length()));
            }
            String[] tokens = line == null ? new String[0] : line.trim().split("\\s+");
            if (tokens.length == 3 && "READY".equals(tokens[0]) && !tokens[2].isBlank()) {
                try {
                    int port = Integer.parseInt(tokens[1]);
                    if (port >= 1 && port <= 65_535) {
                        return new WorkerStartup(port, tokens[2]);
                    }
                } catch (NumberFormatException ignored) {
                    // Report every malformed startup line through the same non-sensitive message.
                }
            }
            throw new IllegalStateException("Worker emitted invalid startup line: " + line);
        }

        static WorkerStartup awaitStartup(
                Process process,
                BufferedReader reader,
                Duration timeout) throws Exception {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> future = executor.submit(reader::readLine);
            boolean success = false;
            boolean restoreInterrupt = false;
            try {
                String line = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                WorkerStartup startup = parseStartupLine(line);
                success = true;
                return startup;
            } catch (InterruptedException exception) {
                restoreInterrupt = true;
                throw exception;
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof Exception nested) {
                    throw nested;
                }
                throw new IllegalStateException("Worker startup read failed", cause);
            } finally {
                if (!success) {
                    future.cancel(true);
                    process.destroyForcibly();
                    try {
                        reader.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        process.waitFor(
                                RuntimeConstants.WORKER_FORCE_KILL_TIMEOUT.toMillis(),
                                TimeUnit.MILLISECONDS);
                    } catch (InterruptedException exception) {
                        restoreInterrupt = true;
                    }
                }
                executor.shutdownNow();
                try {
                    executor.awaitTermination(
                            RuntimeConstants.WORKER_FORCE_KILL_TIMEOUT.toMillis(),
                            TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    restoreInterrupt = true;
                }
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
