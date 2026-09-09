package com.saloidvl.lsp4jmcp.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdtlsSettingsLoader {

    private static final String RUNTIMES = "java.configuration.runtimes";
    private static final String GRADLE_JAVA_HOME = "java.import.gradle.java.home";
    private static final String GRADLE_APT = "java.import.gradle.annotationProcessing.enabled";
    private static final String GRADLE_OFFLINE = "java.import.gradle.offline.enabled";
    private static final String LOMBOK_SUPPORT = "java.jdt.ls.lombokSupport.enabled";
    private static final Pattern JAVA_FEATURE = Pattern.compile("^(?:1\\.)?(\\d+)");

    private JdtlsSettingsLoader() {
    }

    public static JdtlsSettingsSnapshot load(
            Path workspace,
            JdtlsSettingsInputs inputs,
            Path workerJavaHome,
            int workerJavaFeature) throws IOException {
        JdtlsSettingsSource source = JdtlsSettingsSource.capture(
                workspace, inputs, workerJavaHome);
        return load(source, workerJavaFeature);
    }

    public static JdtlsSettingsSnapshot loadDefaults(
            Path workerJavaHome,
            int workerJavaFeature) throws IOException {
        JdtlsSettingsSource source = JdtlsSettingsSource.capture(
                Path.of("."), JdtlsSettingsInputs.empty(), workerJavaHome);
        return load(source, workerJavaFeature);
    }

    private static JdtlsSettingsSnapshot load(
            JdtlsSettingsSource source,
            int workerJavaFeature) throws IOException {
        JsonObject settings = defaults(source.workerJavaHome(), workerJavaFeature);
        applyEnvironmentOverrides(settings, source.inputs());
        mergeSettingsFile(settings, source);
        return JdtlsSettingsSnapshot.of(settings, source.fingerprint());
    }

    private static JsonObject defaults(Path workerJavaHome, int workerJavaFeature) {
        Path normalizedWorkerJavaHome = workerJavaHome.toAbsolutePath().normalize();
        JsonObject settings = new JsonObject();
        set(settings, RUNTIMES, runtime(normalizedWorkerJavaHome, workerJavaFeature));
        set(settings, GRADLE_JAVA_HOME, new JsonPrimitive(normalizedWorkerJavaHome.toString()));
        set(settings, GRADLE_APT, new JsonPrimitive(true));
        set(settings, GRADLE_OFFLINE, new JsonPrimitive(false));
        set(settings, LOMBOK_SUPPORT, new JsonPrimitive(true));
        return settings;
    }

    private static void applyEnvironmentOverrides(
            JsonObject settings,
            JdtlsSettingsInputs inputs) throws IOException {
        if (inputs.runtimeHome() != null) {
            Path runtimeHome = validateJdkHome(
                    JdtlsSettingsInputs.RUNTIME_HOME_ENV, inputs.runtimeHome());
            int runtimeFeature = readJavaFeature(runtimeHome);
            set(settings, RUNTIMES, runtime(runtimeHome, runtimeFeature));
        }
        if (inputs.gradleJavaHome() != null) {
            Path gradleJavaHome = validateJdkHome(
                    JdtlsSettingsInputs.GRADLE_JAVA_HOME_ENV, inputs.gradleJavaHome());
            set(settings, GRADLE_JAVA_HOME, new JsonPrimitive(gradleJavaHome.toString()));
        }
        if (inputs.gradleAptEnabled() != null) {
            set(settings, GRADLE_APT, new JsonPrimitive(parseBoolean(
                    JdtlsSettingsInputs.GRADLE_APT_ENABLED_ENV, inputs.gradleAptEnabled())));
        }
        if (inputs.gradleOfflineEnabled() != null) {
            set(settings, GRADLE_OFFLINE, new JsonPrimitive(parseBoolean(
                    JdtlsSettingsInputs.GRADLE_OFFLINE_ENABLED_ENV,
                    inputs.gradleOfflineEnabled())));
        }
        if (inputs.lombokSupportEnabled() != null) {
            set(settings, LOMBOK_SUPPORT, new JsonPrimitive(parseBoolean(
                    JdtlsSettingsInputs.LOMBOK_SUPPORT_ENABLED_ENV,
                    inputs.lombokSupportEnabled())));
        }
    }

    private static JsonArray runtime(Path javaHome, int feature) {
        JsonObject runtime = new JsonObject();
        runtime.addProperty("name", "JavaSE-" + feature);
        runtime.addProperty("path", javaHome.toString());
        runtime.addProperty("default", true);
        JsonArray runtimes = new JsonArray();
        runtimes.add(runtime);
        return runtimes;
    }

    private static Path validateJdkHome(String environmentName, String rawPath) {
        Path path;
        try {
            path = Path.of(rawPath);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(environmentName + " is not a valid path", ex);
        }
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(environmentName + " must be an absolute path");
        }
        path = path.normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(environmentName + " must name an existing directory");
        }
        if (!Files.isRegularFile(path.resolve("bin/java"))) {
            throw new IllegalArgumentException(
                    environmentName + " must contain a regular bin/java file");
        }
        return path;
    }

    private static int readJavaFeature(Path runtimeHome) throws IOException {
        Path release = runtimeHome.resolve("release");
        if (!Files.isRegularFile(release) || !Files.isReadable(release)) {
            throw new IllegalArgumentException(
                    JdtlsSettingsInputs.RUNTIME_HOME_ENV + " must contain a readable release file");
        }
        List<String> releaseLines;
        try {
            releaseLines = Files.readAllLines(release, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IOException(
                    JdtlsSettingsInputs.RUNTIME_HOME_ENV + " release file could not be read",
                    exception);
        }
        String version = releaseLines.stream()
                .filter(line -> line.startsWith("JAVA_VERSION="))
                .findFirst()
                .map(line -> line.substring("JAVA_VERSION=".length()))
                .map(JdtlsSettingsLoader::stripSurroundingQuotes)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot determine Java version for "
                                + JdtlsSettingsInputs.RUNTIME_HOME_ENV + " from release file"));
        Matcher matcher = JAVA_FEATURE.matcher(version);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Cannot determine Java version for "
                            + JdtlsSettingsInputs.RUNTIME_HOME_ENV + " from release file");
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Cannot determine Java version for "
                            + JdtlsSettingsInputs.RUNTIME_HOME_ENV + " from release file",
                    exception);
        }
    }

    private static String stripSurroundingQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean parseBoolean(String envName, String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new IllegalArgumentException(envName + " must be true or false");
    }

    private static void mergeSettingsFile(
            JsonObject settings,
            JdtlsSettingsSource source) {
        if (source.settingsFileBytes().isEmpty()) {
            return;
        }
        Path path = source.settingsFile().orElseThrow();
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(
                    new String(source.settingsFileBytes().orElseThrow(), StandardCharsets.UTF_8));
        } catch (JsonParseException ex) {
            throw new IllegalArgumentException(
                    "Malformed JDTLS settings JSON in " + path, ex);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException(
                    "JDTLS settings file root must be a JSON object: " + path);
        }
        deepMerge(settings, parsed.getAsJsonObject());
    }

    private static void deepMerge(JsonObject existing, JsonObject incoming) {
        for (var entry : incoming.entrySet()) {
            JsonElement current = existing.get(entry.getKey());
            JsonElement replacement = entry.getValue();
            if (current != null && current.isJsonObject() && replacement.isJsonObject()) {
                deepMerge(current.getAsJsonObject(), replacement.getAsJsonObject());
            } else {
                existing.add(entry.getKey(), replacement.deepCopy());
            }
        }
    }

    private static void set(JsonObject root, String dottedPath, JsonElement value) {
        Objects.requireNonNull(root, "root");
        String[] segments = dottedPath.split("\\.");
        JsonObject current = root;
        for (int index = 0; index < segments.length - 1; index++) {
            JsonElement child = current.get(segments[index]);
            if (child == null || !child.isJsonObject()) {
                JsonObject object = new JsonObject();
                current.add(segments[index], object);
                current = object;
            } else {
                current = child.getAsJsonObject();
            }
        }
        current.add(segments[segments.length - 1], value.deepCopy());
    }
}
