package com.saloidvl.lsp4jmcp.supervisor;

import com.saloidvl.lsp4jmcp.runtime.RuntimeConstants;
import com.saloidvl.lsp4jmcp.worker.RepoWorkerMain;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@FunctionalInterface
public interface WorkerProcessLauncher {
    StartedWorker start(Path workspacePath, String jdtlsCommand) throws Exception;

    record StartedWorker(long workerPid, String host, int port) {
    }

    final class JvmWorkerProcessLauncher implements WorkerProcessLauncher {
        @Override
        public StartedWorker start(Path workspacePath, String jdtlsCommand) throws Exception {
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("java.class.path");

            Process process = new ProcessBuilder(
                javaBin,
                "-cp",
                classpath,
                RepoWorkerMain.class.getName(),
                workspacePath.toString(),
                jdtlsCommand
            )
                .redirectErrorStream(true)
                .start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<String> future = executor.submit(reader::readLine);
                String firstLine = future.get(RuntimeConstants.WORKER_STARTUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (firstLine == null || !firstLine.startsWith("READY ")) {
                    process.destroyForcibly();
                    throw new IllegalStateException("Worker did not emit READY line");
                }

                int port = Integer.parseInt(firstLine.substring("READY ".length()).trim());
                return new StartedWorker(process.pid(), "127.0.0.1", port);
            }
        }
    }
}
