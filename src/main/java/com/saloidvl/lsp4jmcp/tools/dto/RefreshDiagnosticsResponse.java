package com.saloidvl.lsp4jmcp.tools.dto;

import com.google.gson.annotations.SerializedName;

public record RefreshDiagnosticsResponse(
    String status,
    @SerializedName("build_duration_ms") long buildDurationMs
) {
}
