package com.saloidvl.lsp4jmcp.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LauncherMainTest {

    @Test
    void mainMethodExists() throws Exception {
        var mainMethod = LauncherMain.class.getMethod("main", String[].class);

        assertThat(mainMethod).isNotNull();
        assertThat(mainMethod.getReturnType()).isEqualTo(void.class);
    }

    @Test
    void shadedJarMainClass_pointsToLauncherMain() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).contains("<mainClass>com.saloidvl.lsp4jmcp.launcher.LauncherMain</mainClass>");
    }
}
