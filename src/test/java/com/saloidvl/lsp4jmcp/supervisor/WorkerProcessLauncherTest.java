package com.saloidvl.lsp4jmcp.supervisor;

import com.saloidvl.lsp4jmcp.config.JdtlsSettingsInputs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerProcessLauncherTest {

    @Test
    void buildProcessBuilder_removesInheritedSettingsAndInstallsRequestValues(
            @TempDir Path tempDir) {
        JdtlsSettingsInputs requested = new JdtlsSettingsInputs(
                "config/jdtls.json", "/jdk-21", null, "false", null, null);

        ProcessBuilder builder = WorkerProcessLauncher.JvmWorkerProcessLauncher.buildProcessBuilder(
                tempDir, "jdtls", requested, "/java/bin/java", "test-classpath");

        assertThat(builder.environment())
                .containsEntry(JdtlsSettingsInputs.SETTINGS_FILE_ENV, "config/jdtls.json")
                .containsEntry(JdtlsSettingsInputs.RUNTIME_HOME_ENV, "/jdk-21")
                .containsEntry(JdtlsSettingsInputs.GRADLE_APT_ENABLED_ENV, "false")
                .doesNotContainKeys(JdtlsSettingsInputs.GRADLE_JAVA_HOME_ENV);
    }

    @Test
    void parseReadyLine_returnsPortAndFingerprint() {
        WorkerProcessLauncher.WorkerStartup startup =
                WorkerProcessLauncher.JvmWorkerProcessLauncher
                        .parseStartupLine("READY 45123 abcdef12");

        assertThat(startup.port()).isEqualTo(45123);
        assertThat(startup.settingsFingerprint()).isEqualTo("abcdef12");
    }

    @Test
    void parseReadyLine_propagatesWorkerError() {
        assertThatThrownBy(() -> WorkerProcessLauncher.JvmWorkerProcessLauncher
                .parseStartupLine(
                        "ERROR LSP4JMCP_JDTLS_GRADLE_APT_ENABLED must be true or false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LSP4JMCP_JDTLS_GRADLE_APT_ENABLED must be true or false");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "READY",
            "READY 45123",
            "READY not-a-port fingerprint",
            "READY 0 fingerprint",
            " ",
            "INFO starting"
    })
    void parseReadyLine_rejectsMalformedStartupLines(String line) {
        assertThatThrownBy(() -> WorkerProcessLauncher.JvmWorkerProcessLauncher
                .parseStartupLine(line))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Worker emitted invalid startup line:")
                .hasMessageContaining(line);
    }

    @Test
    void awaitStartup_timeoutKillsSilentWorkerWithinBound() throws Exception {
        FakeSilentProcess process = new FakeSilentProcess();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> WorkerProcessLauncher.JvmWorkerProcessLauncher
                .awaitStartup(process, reader, Duration.ofMillis(100)))
                .isInstanceOf(TimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .isLessThan(Duration.ofSeconds(1));
        assertThat(process.isAlive()).isFalse();
        assertThat(process.forciblyDestroyed).isTrue();
    }

    private static final class FakeSilentProcess extends Process {
        private final CountDownLatch inputClosed = new CountDownLatch(1);
        private final CountDownLatch exited = new CountDownLatch(1);
        private final InputStream input = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    inputClosed.await();
                    return -1;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }

            @Override
            public void close() {
                inputClosed.countDown();
            }
        };
        private volatile boolean alive = true;
        private volatile boolean forciblyDestroyed;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return 0;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            forciblyDestroyed = true;
            alive = false;
            inputClosed.countDown();
            exited.countDown();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
