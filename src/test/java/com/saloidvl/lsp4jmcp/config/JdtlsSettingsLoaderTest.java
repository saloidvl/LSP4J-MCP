package com.saloidvl.lsp4jmcp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdtlsSettingsLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void inputs_captureSixNamedVariablesAndIgnoreBlankValues() {
        assertThat(JdtlsSettingsInputs.SETTINGS_FILE_ENV)
                .isEqualTo("LSP4JMCP_JDTLS_SETTINGS_FILE");
        Map<String, String> env = new HashMap<>();
        env.put(JdtlsSettingsInputs.SETTINGS_FILE_ENV, " config/jdtls.json ");
        env.put(JdtlsSettingsInputs.RUNTIME_HOME_ENV, "/jdks/21");
        env.put(JdtlsSettingsInputs.GRADLE_JAVA_HOME_ENV, " ");
        env.put(JdtlsSettingsInputs.GRADLE_APT_ENABLED_ENV, "false");
        env.put(JdtlsSettingsInputs.GRADLE_OFFLINE_ENABLED_ENV, "true");
        env.put(JdtlsSettingsInputs.LOMBOK_SUPPORT_ENABLED_ENV, "false");
        env.put("UNRELATED", "ignored");

        JdtlsSettingsInputs inputs = JdtlsSettingsInputs.fromEnvironment(env);

        assertThat(inputs.settingsFile()).isEqualTo("config/jdtls.json");
        assertThat(inputs.runtimeHome()).isEqualTo("/jdks/21");
        assertThat(inputs.gradleJavaHome()).isNull();
        assertThat(inputs.gradleAptEnabled()).isEqualTo("false");
        assertThat(inputs.gradleOfflineEnabled()).isEqualTo("true");
        assertThat(inputs.lombokSupportEnabled()).isEqualTo("false");
    }

    @Test
    void inputs_installIntoClearsStaleKnownValuesBeforeAddingRequestedValues() {
        Map<String, String> child = new HashMap<>();
        child.put(JdtlsSettingsInputs.RUNTIME_HOME_ENV, "/stale/jdk");
        child.put(JdtlsSettingsInputs.GRADLE_APT_ENABLED_ENV, "false");
        child.put("PATH", "/usr/bin");

        JdtlsSettingsInputs.empty().installInto(child);

        assertThat(child).doesNotContainKeys(
                JdtlsSettingsInputs.RUNTIME_HOME_ENV,
                JdtlsSettingsInputs.GRADLE_APT_ENABLED_ENV);
        assertThat(child).containsEntry("PATH", "/usr/bin");
    }

    @Test
    void source_resolvesRelativeFileFromWorkspaceAndHashesExactBytes() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path config = Files.createDirectories(workspace.resolve("config")).resolve("jdtls.json");
        Files.writeString(config, "{\"java\":{}}", StandardCharsets.UTF_8);
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                "config/jdtls.json", null, null, null, null, null);

        JdtlsSettingsSource first = JdtlsSettingsSource.capture(
                workspace, inputs, Path.of("/worker/jdk"));
        Files.writeString(config, "{ \"java\": {} }", StandardCharsets.UTF_8);
        JdtlsSettingsSource second = JdtlsSettingsSource.capture(
                workspace, inputs, Path.of("/worker/jdk"));

        assertThat(first.settingsFile()).contains(config.toAbsolutePath().normalize());
        assertThat(first.settingsFileBytes()).hasValueSatisfying(bytes ->
                assertThat(bytes).isEqualTo("{\"java\":{}}".getBytes(StandardCharsets.UTF_8)));
        assertThat(first.fingerprint()).isNotEqualTo(second.fingerprint());
    }

    @Test
    void source_resolvesAbsoluteFileWithoutPrependingWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path config = tempDir.resolve("external-settings.json").toAbsolutePath();
        Files.writeString(config, "{}", StandardCharsets.UTF_8);
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                config.toString(), null, null, null, null, null);

        JdtlsSettingsSource source = JdtlsSettingsSource.capture(
                workspace, inputs, Path.of("/worker/jdk"));

        assertThat(source.settingsFile()).contains(config.normalize());
    }

    @Test
    void source_fingerprintChangesForWorkerJavaHomeAndEverySettingInput() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path settings = workspace.resolve("settings.json");
        Files.writeString(settings, "{}", StandardCharsets.UTF_8);
        JdtlsSettingsInputs base = JdtlsSettingsInputs.empty();
        String baseline = JdtlsSettingsSource.capture(
                workspace, base, Path.of("/worker/jdk-21")).fingerprint();

        assertThat(JdtlsSettingsSource.capture(
                workspace, base, Path.of("/worker/jdk-22")).fingerprint())
                .isNotEqualTo(baseline);
        assertThat(fingerprint(workspace,
                new JdtlsSettingsInputs(null, "/jdk", null, null, null, null)))
                .isNotEqualTo(baseline);
        assertThat(fingerprint(workspace,
                new JdtlsSettingsInputs(null, null, "/gradle-jdk", null, null, null)))
                .isNotEqualTo(baseline);
        assertThat(fingerprint(workspace,
                new JdtlsSettingsInputs(null, null, null, "true", null, null)))
                .isNotEqualTo(baseline);
        assertThat(fingerprint(workspace,
                new JdtlsSettingsInputs(null, null, null, null, "true", null)))
                .isNotEqualTo(baseline);
        assertThat(fingerprint(workspace,
                new JdtlsSettingsInputs(null, null, null, null, null, "false")))
                .isNotEqualTo(baseline);
        assertThat(fingerprint(workspace,
                new JdtlsSettingsInputs("settings.json", null, null, null, null, null)))
                .isNotEqualTo(baseline);
    }

    @Test
    void source_fingerprintIsStableForUnchangedInputsAndFileBytes() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Files.writeString(workspace.resolve("settings.json"), "{\"java\":{}}",
                StandardCharsets.UTF_8);
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                "settings.json", null, null, null, null, null);

        assertThat(JdtlsSettingsSource.capture(
                workspace, inputs, Path.of("/worker/jdk")).fingerprint())
                .isEqualTo(JdtlsSettingsSource.capture(
                        workspace, inputs, Path.of("/worker/jdk")).fingerprint());
    }

    @Test
    void load_buildsAllFiveDefaultsFromWorkerJvm() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path workerHome = fakeJdk("worker-jdk", "21.0.8");

        JsonObject settings = JdtlsSettingsLoader
                .load(workspace, JdtlsSettingsInputs.empty(), workerHome, 21)
                .settingsCopy();

        JsonObject java = settings.getAsJsonObject("java");
        JsonObject runtime = java.getAsJsonObject("configuration")
                .getAsJsonArray("runtimes").get(0).getAsJsonObject();
        assertThat(runtime.get("name").getAsString()).isEqualTo("JavaSE-21");
        assertThat(runtime.get("path").getAsString()).isEqualTo(workerHome.toString());
        assertThat(runtime.get("default").getAsBoolean()).isTrue();
        assertThat(java.getAsJsonObject("import").getAsJsonObject("gradle")
                .getAsJsonObject("java").get("home").getAsString()).isEqualTo(workerHome.toString());
        assertThat(java.getAsJsonObject("import").getAsJsonObject("gradle")
                .getAsJsonObject("annotationProcessing").get("enabled").getAsBoolean()).isTrue();
        assertThat(java.getAsJsonObject("import").getAsJsonObject("gradle")
                .getAsJsonObject("offline").get("enabled").getAsBoolean()).isFalse();
        assertThat(java.getAsJsonObject("jdt").getAsJsonObject("ls")
                .getAsJsonObject("lombokSupport").get("enabled").getAsBoolean()).isTrue();
    }

    @Test
    void load_fileDeepMergesOverEnvironmentAndReplacesArrays() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path workerHome = fakeJdk("worker-jdk", "21.0.8");
        Path runtimeHome = fakeJdk("runtime-jdk", "17.0.12");
        Files.writeString(workspace.resolve("settings.json"), """
                {"java":{"configuration":{"runtimes":[{"name":"JavaSE-11","path":"/jdk-11","default":true}]},
                "import":{"gradle":{"annotationProcessing":{"enabled":true}}},"completion":{"favoriteStaticMembers":["x.Y.*"]}}}
                """, StandardCharsets.UTF_8);
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                "settings.json", runtimeHome.toString(), null, "false", "true", "false");

        JsonObject java = JdtlsSettingsLoader.load(workspace, inputs, workerHome, 21)
                .settingsCopy().getAsJsonObject("java");

        assertThat(java.getAsJsonObject("configuration").getAsJsonArray("runtimes")).hasSize(1);
        assertThat(java.getAsJsonObject("configuration").getAsJsonArray("runtimes")
                .get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("JavaSE-11");
        assertThat(java.getAsJsonObject("import").getAsJsonObject("gradle")
                .getAsJsonObject("annotationProcessing").get("enabled").getAsBoolean()).isTrue();
        assertThat(java.getAsJsonObject("import").getAsJsonObject("gradle")
                .getAsJsonObject("offline").get("enabled").getAsBoolean()).isTrue();
        assertThat(java.getAsJsonObject("completion").getAsJsonArray("favoriteStaticMembers")).hasSize(1);
    }

    @Test
    void load_rejectsInvalidBooleanWithoutSilentlyTreatingItAsFalse() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path workerHome = fakeJdk("worker-jdk", "21.0.8");
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                null, null, null, "yes", null, null);

        assertThatThrownBy(() -> JdtlsSettingsLoader.load(workspace, inputs, workerHome, 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(JdtlsSettingsInputs.GRADLE_APT_ENABLED_ENV)
                .hasMessageContaining("true or false");
    }

    @Test
    void load_acceptsCaseInsensitiveBooleansAndGradleJavaHome() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path workerHome = fakeJdk("worker-jdk", "21.0.8");
        Path gradleHome = fakeJdk("gradle-jdk", "17.0.12");
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                null, null, gradleHome.toString(), "TRUE", "False", null);

        JsonObject gradle = JdtlsSettingsLoader.load(workspace, inputs, workerHome, 21)
                .settingsCopy().getAsJsonObject("java").getAsJsonObject("import")
                .getAsJsonObject("gradle");

        assertThat(gradle.getAsJsonObject("java").get("home").getAsString())
                .isEqualTo(gradleHome.toString());
        assertThat(gradle.getAsJsonObject("annotationProcessing")
                .get("enabled").getAsBoolean()).isTrue();
        assertThat(gradle.getAsJsonObject("offline").get("enabled").getAsBoolean()).isFalse();
    }

    @Test
    void load_fileCanReplaceLowerPriorityPrimitiveWithJsonNull() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path workerHome = fakeJdk("worker-jdk", "21.0.8");
        Files.writeString(workspace.resolve("settings.json"),
                "{\"java\":{\"import\":{\"gradle\":{\"offline\":{\"enabled\":null}}}}}",
                StandardCharsets.UTF_8);
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                "settings.json", null, null, null, "true", null);

        JsonElement enabled = JdtlsSettingsLoader.load(workspace, inputs, workerHome, 21)
                .settingsCopy().getAsJsonObject("java").getAsJsonObject("import")
                .getAsJsonObject("gradle").getAsJsonObject("offline").get("enabled");

        assertThat(enabled.isJsonNull()).isTrue();
    }

    @Test
    void snapshot_returnsDefensiveDeepCopies() {
        JsonObject original = JsonParser.parseString("{\"java\":{\"x\":1}}")
                .getAsJsonObject();
        JdtlsSettingsSnapshot snapshot = JdtlsSettingsSnapshot.of(original, "fingerprint");
        original.getAsJsonObject("java").addProperty("x", 2);
        JsonObject first = snapshot.settingsCopy();
        first.getAsJsonObject("java").addProperty("x", 3);

        assertThat(snapshot.settingsCopy().getAsJsonObject("java").get("x").getAsInt())
                .isEqualTo(1);
        assertThat(snapshot.sourceFingerprint()).isEqualTo("fingerprint");
    }

    @Test
    void load_rejectsRelativeRuntimeHome() throws Exception {
        assertInvalidHome(
                new JdtlsSettingsInputs(null, "relative-jdk", null, null, null, null),
                JdtlsSettingsInputs.RUNTIME_HOME_ENV);
    }

    @Test
    void load_rejectsRelativeGradleJavaHome() throws Exception {
        assertInvalidHome(
                new JdtlsSettingsInputs(null, null, "relative-jdk", null, null, null),
                JdtlsSettingsInputs.GRADLE_JAVA_HOME_ENV);
    }

    @Test
    void load_rejectsMissingRuntimeHomeDirectory() throws Exception {
        Path missing = tempDir.resolve("missing-runtime").toAbsolutePath();
        assertInvalidHome(
                new JdtlsSettingsInputs(null, missing.toString(), null, null, null, null),
                JdtlsSettingsInputs.RUNTIME_HOME_ENV);
    }

    @Test
    void load_rejectsMissingGradleJavaHomeDirectory() throws Exception {
        Path missing = tempDir.resolve("missing-gradle").toAbsolutePath();
        assertInvalidHome(
                new JdtlsSettingsInputs(null, null, missing.toString(), null, null, null),
                JdtlsSettingsInputs.GRADLE_JAVA_HOME_ENV);
    }

    @Test
    void load_rejectsRuntimeHomeWithoutJavaBinary() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("runtime-no-java")).toAbsolutePath();
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"17.0.12\"\n");
        assertInvalidHome(
                new JdtlsSettingsInputs(null, home.toString(), null, null, null, null),
                JdtlsSettingsInputs.RUNTIME_HOME_ENV);
    }

    @Test
    void load_rejectsGradleJavaHomeWithoutJavaBinary() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("gradle-no-java")).toAbsolutePath();
        assertInvalidHome(
                new JdtlsSettingsInputs(null, null, home.toString(), null, null, null),
                JdtlsSettingsInputs.GRADLE_JAVA_HOME_ENV);
    }

    @Test
    void load_rejectsRuntimeHomeWithoutReleaseFile() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("runtime-no-release")).toAbsolutePath();
        Path java = Files.createDirectories(home.resolve("bin")).resolve("java");
        Files.writeString(java, "fake java binary");
        assertInvalidHome(
                new JdtlsSettingsInputs(null, home.toString(), null, null, null, null),
                JdtlsSettingsInputs.RUNTIME_HOME_ENV);
    }

    @Test
    void load_rejectsOutOfRangeRuntimeVersionWithActionableMessage() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path workerHome = fakeJdk("worker-jdk", "21.0.8");
        Path runtimeHome = fakeJdk("overflow-runtime", "999999999999999999999999");
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                null, runtimeHome.toString(), null, null, null, null);

        assertThatThrownBy(() -> JdtlsSettingsLoader.load(workspace, inputs, workerHome, 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot determine Java version")
                .hasMessageContaining(JdtlsSettingsInputs.RUNTIME_HOME_ENV)
                .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void load_rejectsMissingSettingsFileWithNormalizedPath() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path workerHome = fakeJdk("worker-jdk", "21.0.8");
        Path missing = workspace.resolve("config/../missing.json").normalize();
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                "config/../missing.json", null, null, null, null, null);

        assertThatThrownBy(() -> JdtlsSettingsLoader.load(workspace, inputs, workerHome, 21))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining(missing.toString());
    }

    @Test
    void load_rejectsMalformedJsonWithNormalizedPath() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path workerHome = fakeJdk("worker-jdk", "21.0.8");
        Path settings = workspace.resolve("settings.json").toAbsolutePath().normalize();
        Files.writeString(settings, "{malformed", StandardCharsets.UTF_8);
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                "settings.json", null, null, null, null, null);

        assertThatThrownBy(() -> JdtlsSettingsLoader.load(workspace, inputs, workerHome, 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed JDTLS settings JSON")
                .hasMessageContaining(settings.toString());
    }

    @Test
    void load_rejectsJsonArrayAtFileRootWithNormalizedPath() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path workerHome = fakeJdk("worker-jdk", "21.0.8");
        Path settings = workspace.resolve("settings.json").toAbsolutePath().normalize();
        Files.writeString(settings, "[]", StandardCharsets.UTF_8);
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                "settings.json", null, null, null, null, null);

        assertThatThrownBy(() -> JdtlsSettingsLoader.load(workspace, inputs, workerHome, 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root must be a JSON object")
                .hasMessageContaining(settings.toString());
    }

    @Test
    void load_reportsReadFailureForDirectorySettingsPath() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path workerHome = fakeJdk("worker-jdk", "21.0.8");
        Path directory = Files.createDirectories(workspace.resolve("settings.json"))
                .toAbsolutePath().normalize();
        JdtlsSettingsInputs inputs = new JdtlsSettingsInputs(
                "settings.json", null, null, null, null, null);

        assertThatThrownBy(() -> JdtlsSettingsLoader.load(workspace, inputs, workerHome, 21))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining(directory.toString());
    }

    private void assertInvalidHome(JdtlsSettingsInputs inputs, String environmentName)
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("repo"));
        Path workerHome = fakeJdk("worker-jdk", "21.0.8");
        String rejectedValue = inputs.runtimeHome() != null
                ? inputs.runtimeHome()
                : inputs.gradleJavaHome();

        assertThatThrownBy(() -> JdtlsSettingsLoader.load(workspace, inputs, workerHome, 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(environmentName)
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain(rejectedValue));
    }

    private Path fakeJdk(String directory, String version) throws java.io.IOException {
        Path home = Files.createDirectories(tempDir.resolve(directory));
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        Path java = Files.createDirectories(home.resolve("bin")).resolve("java");
        Files.writeString(java, "fake java binary");
        return home.toAbsolutePath();
    }

    private String fingerprint(Path workspace, JdtlsSettingsInputs inputs) throws Exception {
        return JdtlsSettingsSource.capture(
                workspace, inputs, Path.of("/worker/jdk-21")).fingerprint();
    }
}
