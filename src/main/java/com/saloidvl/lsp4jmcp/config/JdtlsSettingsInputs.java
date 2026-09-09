package com.saloidvl.lsp4jmcp.config;

import java.util.List;
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

    private static final List<String> ENVIRONMENT_KEYS = List.of(
            SETTINGS_FILE_ENV,
            RUNTIME_HOME_ENV,
            GRADLE_JAVA_HOME_ENV,
            GRADLE_APT_ENABLED_ENV,
            GRADLE_OFFLINE_ENABLED_ENV,
            LOMBOK_SUPPORT_ENABLED_ENV);

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
                environment.get(ENVIRONMENT_KEYS.get(0)),
                environment.get(ENVIRONMENT_KEYS.get(1)),
                environment.get(ENVIRONMENT_KEYS.get(2)),
                environment.get(ENVIRONMENT_KEYS.get(3)),
                environment.get(ENVIRONMENT_KEYS.get(4)),
                environment.get(ENVIRONMENT_KEYS.get(5)));
    }

    public static JdtlsSettingsInputs empty() {
        return new JdtlsSettingsInputs(null, null, null, null, null, null);
    }

    public void installInto(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        ENVIRONMENT_KEYS.forEach(environment::remove);
        putIfPresent(environment, SETTINGS_FILE_ENV, settingsFile);
        putIfPresent(environment, RUNTIME_HOME_ENV, runtimeHome);
        putIfPresent(environment, GRADLE_JAVA_HOME_ENV, gradleJavaHome);
        putIfPresent(environment, GRADLE_APT_ENABLED_ENV, gradleAptEnabled);
        putIfPresent(environment, GRADLE_OFFLINE_ENABLED_ENV, gradleOfflineEnabled);
        putIfPresent(environment, LOMBOK_SUPPORT_ENABLED_ENV, lombokSupportEnabled);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void putIfPresent(Map<String, String> environment, String key, String value) {
        if (value != null) {
            environment.put(key, value);
        }
    }
}
