package com.saloidvl.lsp4jmcp.config;

import com.google.gson.JsonObject;

import java.util.Objects;

public final class JdtlsSettingsSnapshot {

    private final JsonObject settings;
    private final String sourceFingerprint;

    private JdtlsSettingsSnapshot(JsonObject settings, String sourceFingerprint) {
        this.settings = settings.deepCopy();
        this.sourceFingerprint = sourceFingerprint;
    }

    public static JdtlsSettingsSnapshot of(JsonObject settings, String sourceFingerprint) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
        if (sourceFingerprint.isBlank()) {
            throw new IllegalArgumentException("sourceFingerprint must not be blank");
        }
        return new JdtlsSettingsSnapshot(settings, sourceFingerprint);
    }

    public JsonObject settingsCopy() {
        return settings.deepCopy();
    }

    public String sourceFingerprint() {
        return sourceFingerprint;
    }
}
