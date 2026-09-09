package com.saloidvl.lsp4jmcp.launcher;

import com.saloidvl.lsp4jmcp.config.JdtlsSettingsInputs;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LauncherMainTest {

    @Test
    void readSettingsInputs_capturesCurrentLauncherEnvironment() {
        Map<String, String> env = Map.of(
                JdtlsSettingsInputs.SETTINGS_FILE_ENV, "config/jdtls.json",
                JdtlsSettingsInputs.GRADLE_APT_ENABLED_ENV, "false");

        JdtlsSettingsInputs inputs = LauncherMain.readSettingsInputs(env);

        assertThat(inputs.settingsFile()).isEqualTo("config/jdtls.json");
        assertThat(inputs.gradleAptEnabled()).isEqualTo("false");
    }

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
