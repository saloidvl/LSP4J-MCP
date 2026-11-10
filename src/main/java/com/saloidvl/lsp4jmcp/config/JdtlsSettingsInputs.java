package com.saloidvl.lsp4jmcp.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record JdtlsSettingsInputs(
        String settingsFile,
        String runtimeHome,
        String gradleJavaHome,
        String gradleAptEnabled,
        String gradleOfflineEnabled,
        String lombokSupportEnabled) {

    public static final String SETTINGS_FILE_ENV = "LSP4JMCP_JDTLS_SETTINGS_FILE";
    public static final String RUNTIME_HOME_ENV = "LSP4JMCP_JDTLS_RUNTIME_HOME";
    public static final String GRADLE_JAVA_HOME_ENV = "LSP4JMCP_JDTLS_GRADLE_JAVA_HOME";
    public static final String GRADLE_APT_ENABLED_ENV = "LSP4JMCP_JDTLS_GRADLE_APT_ENABLED";
    public static final String GRADLE_OFFLINE_ENABLED_ENV = "LSP4JMCP_JDTLS_GRADLE_OFFLINE_ENABLED";
    public static final String LOMBOK_SUPPORT_ENABLED_ENV = "LSP4JMCP_JDTLS_LOMBOK_SUPPORT_ENABLED";

    public JdtlsSettingsInputs {
        settingsFile = normalize(settingsFile);
        runtimeHome = normalize(runtimeHome);
        gradleJavaHome = normalize(gradleJavaHome);
        gradleAptEnabled = normalize(gradleAptEnabled);
        gradleOfflineEnabled = normalize(gradleOfflineEnabled);
        lombokSupportEnabled = normalize(lombokSupportEnabled);
    }

    public static JdtlsSettingsInputs fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new JdtlsSettingsInputs(
                absolutePath(environment.get(SETTINGS_FILE_ENV)),
                absolutePath(environment.get(RUNTIME_HOME_ENV)),
                absolutePath(environment.get(GRADLE_JAVA_HOME_ENV)),
                environment.get(GRADLE_APT_ENABLED_ENV),
                environment.get(GRADLE_OFFLINE_ENABLED_ENV),
                environment.get(LOMBOK_SUPPORT_ENABLED_ENV));
    }

    public static JdtlsSettingsInputs empty() {
        return new JdtlsSettingsInputs(null, null, null, null, null, null);
    }

    public void installInto(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        putOrRemove(environment, SETTINGS_FILE_ENV, settingsFile);
        putOrRemove(environment, RUNTIME_HOME_ENV, runtimeHome);
        putOrRemove(environment, GRADLE_JAVA_HOME_ENV, gradleJavaHome);
        putOrRemove(environment, GRADLE_APT_ENABLED_ENV, gradleAptEnabled);
        putOrRemove(environment, GRADLE_OFFLINE_ENABLED_ENV, gradleOfflineEnabled);
        putOrRemove(environment, LOMBOK_SUPPORT_ENABLED_ENV, lombokSupportEnabled);
    }

    private static String absolutePath(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : Path.of(normalized).toAbsolutePath().toString();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void putOrRemove(Map<String, String> environment, String key, String value) {
        if (value != null) {
            environment.put(key, value);
        } else {
            environment.remove(key);
        }
    }
}
