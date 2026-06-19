package com.saloidvl.lsp4jmcp.tools.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class DiagnosticsResponse {
    public record ForFile(
        String file,
        List<DiagnosticEntry> diagnostics,
        boolean cached,
        String timestamp,
        @SerializedName("cache_updated_at_ms") long cacheUpdatedAtMs
    ) {
    }

    public record FileSummary(String file, int errors, int warnings) {
    }

    public record Summary(
        List<FileSummary> files,
        boolean cached,
        @SerializedName("cache_updated_at_ms") long cacheUpdatedAtMs
    ) {
    }

    public record FileEntry(String file, List<DiagnosticEntry> diagnostics) {
    }

    public record Full(
        List<FileEntry> files,
        boolean cached,
        @SerializedName("cache_updated_at_ms") long cacheUpdatedAtMs
    ) {
    }
}
