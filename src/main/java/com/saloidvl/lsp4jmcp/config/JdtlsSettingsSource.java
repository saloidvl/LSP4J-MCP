package com.saloidvl.lsp4jmcp.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

public final class JdtlsSettingsSource {

    private static final String FINGERPRINT_VERSION = "jdtls-settings-v1";

    private final Path settingsFile;
    private final byte[] settingsFileBytes;
    private final JdtlsSettingsInputs inputs;
    private final Path workerJavaHome;
    private final String fingerprint;

    private JdtlsSettingsSource(
            Path settingsFile,
            byte[] settingsFileBytes,
            JdtlsSettingsInputs inputs,
            Path workerJavaHome,
            String fingerprint) {
        this.settingsFile = settingsFile;
        this.settingsFileBytes = settingsFileBytes == null ? null : settingsFileBytes.clone();
        this.inputs = inputs;
        this.workerJavaHome = workerJavaHome;
        this.fingerprint = fingerprint;
    }

    public static JdtlsSettingsSource capture(
            Path workspace,
            JdtlsSettingsInputs inputs,
            Path workerJavaHome) throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(workerJavaHome, "workerJavaHome");

        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        Path normalizedWorkerJavaHome = workerJavaHome.toAbsolutePath().normalize();
        Path normalizedSettingsFile = resolveSettingsFile(normalizedWorkspace, inputs.settingsFile());
        byte[] fileBytes = readSettingsFile(normalizedSettingsFile);
        String fingerprint = fingerprint(
                normalizedWorkerJavaHome, inputs, normalizedSettingsFile, fileBytes);
        return new JdtlsSettingsSource(
                normalizedSettingsFile, fileBytes, inputs, normalizedWorkerJavaHome, fingerprint);
    }

    public Optional<Path> settingsFile() {
        return Optional.ofNullable(settingsFile);
    }

    public Optional<byte[]> settingsFileBytes() {
        return settingsFileBytes == null
                ? Optional.empty()
                : Optional.of(settingsFileBytes.clone());
    }

    public JdtlsSettingsInputs inputs() {
        return inputs;
    }

    public Path workerJavaHome() {
        return workerJavaHome;
    }

    public String fingerprint() {
        return fingerprint;
    }

    private static Path resolveSettingsFile(Path workspace, String configuredPath) {
        if (configuredPath == null) {
            return null;
        }
        Path path = Path.of(configuredPath);
        if (!path.isAbsolute()) {
            path = workspace.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static byte[] readSettingsFile(Path settingsFile) throws IOException {
        if (settingsFile == null) {
            return null;
        }
        if (!Files.exists(settingsFile)) {
            throw new IOException("JDTLS settings file does not exist: " + settingsFile);
        }
        try {
            return Files.readAllBytes(settingsFile);
        } catch (IOException ex) {
            throw new IOException("Cannot read JDTLS settings file: " + settingsFile, ex);
        }
    }

    private static String fingerprint(
            Path workerJavaHome,
            JdtlsSettingsInputs inputs,
            Path settingsFile,
            byte[] settingsFileBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, FINGERPRINT_VERSION);
            update(digest, workerJavaHome.toString());
            update(digest, inputs.runtimeHome());
            update(digest, inputs.gradleJavaHome());
            update(digest, inputs.gradleAptEnabled());
            update(digest, inputs.gradleOfflineEnabled());
            update(digest, inputs.lombokSupportEnabled());
            update(digest, settingsFile == null ? null : settingsFile.toString());
            update(digest, settingsFileBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static void update(MessageDigest digest, String value) {
        update(digest, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    private static void update(MessageDigest digest, byte[] value) {
        int length = value == null ? -1 : value.length;
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
        if (value != null) {
            digest.update(value);
        }
    }
}
