package com.saloidvl.lsp4jmcp.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class JdtlsSessionManagerCommandTest {

    @TempDir
    Path dataDir;

    @Test
    void buildCommand_containsJdtlsCommandAndDataDir() {
        List<String> cmd = JdtlsSessionManager.buildCommand("jdtls", dataDir, Optional.empty());

        assertThat(cmd).startsWith("jdtls");
        assertThat(cmd).containsSequence("-data", dataDir.toString());
    }

    @Test
    void buildCommand_appendsJvmArgJavaagentWhenLombokJarPresent() {
        Path jar = dataDir.resolve("lombok-1.18.30.jar");

        List<String> cmd = JdtlsSessionManager.buildCommand("jdtls", dataDir, Optional.of(jar));

        assertThat(cmd).contains("--jvm-arg=-javaagent:" + jar);
        assertThat(cmd).doesNotContain("-vmargs");
    }

    @Test
    void buildCommand_doesNotAppendJvmArgWhenNoLombokJar() {
        List<String> cmd = JdtlsSessionManager.buildCommand("jdtls", dataDir, Optional.empty());

        assertThat(cmd).noneMatch(s -> s.startsWith("--jvm-arg="));
        assertThat(cmd).doesNotContain("-vmargs");
    }

    @Test
    void buildCommand_skipsJavaagentWhenJdtlsCommandAlreadyContainsVmargs() {
        Path jar = dataDir.resolve("lombok.jar");

        List<String> cmd = JdtlsSessionManager.buildCommand(
            "jdtls -vmargs -Xmx512m", dataDir, Optional.of(jar));

        assertThat(cmd.stream().filter("-vmargs"::equals).count()).isEqualTo(1);
        assertThat(cmd).noneMatch(s -> s.startsWith("-javaagent:"));
    }

    @Test
    void buildCommand_splitsMultiWordJdtlsCommand() {
        List<String> cmd = JdtlsSessionManager.buildCommand(
            "/usr/bin/jdtls --some-arg", dataDir, Optional.empty());

        assertThat(cmd).startsWith("/usr/bin/jdtls", "--some-arg");
    }
}
