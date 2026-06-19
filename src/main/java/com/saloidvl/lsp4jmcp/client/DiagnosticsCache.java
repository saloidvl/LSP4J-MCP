package com.saloidvl.lsp4jmcp.client;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class DiagnosticsCache {

    public record Entry(List<Diagnostic> diagnostics, Instant timestamp) {}
    public record Summary(int errors, int warnings) {}

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private volatile long lastUpdatedMs = 0;

    public synchronized void update(String uri, List<Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            cache.remove(uri);
        } else {
            cache.put(uri, new Entry(List.copyOf(diagnostics), Instant.now()));
        }
        lastUpdatedMs = System.currentTimeMillis();
    }

    public Map<String, Entry> getAll() {
        return Collections.unmodifiableMap(cache);
    }

    public Optional<Entry> getForUri(String uri) {
        return Optional.ofNullable(cache.get(uri));
    }

    public Map<String, Summary> getSummary() {
        Map<String, Summary> result = new LinkedHashMap<>();
        cache.forEach((uri, entry) -> {
            int errors = (int) entry.diagnostics().stream()
                .filter(d -> d.getSeverity() == null || d.getSeverity() == DiagnosticSeverity.Error)
                .count();
            int warnings = (int) entry.diagnostics().stream()
                .filter(d -> d.getSeverity() == DiagnosticSeverity.Warning)
                .count();
            result.put(uri, new Summary(errors, warnings));
        });
        return Collections.unmodifiableMap(result);
    }

    public long getLastUpdatedMs() {
        return lastUpdatedMs;
    }

    public synchronized void clear() {
        cache.clear();
        lastUpdatedMs = 0;
    }
}
