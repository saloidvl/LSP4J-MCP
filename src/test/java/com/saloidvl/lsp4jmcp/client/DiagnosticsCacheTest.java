package com.saloidvl.lsp4jmcp.client;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticsCacheTest {

    private DiagnosticsCache cache;

    @BeforeEach
    void setUp() {
        cache = new DiagnosticsCache();
    }

    @Test
    void update_addsDiagnosticsForUri() {
        List<Diagnostic> diags = List.of(makeDiagnostic(DiagnosticSeverity.Error));
        cache.update("file:///Foo.java", diags);
        assertThat(cache.getForUri("file:///Foo.java")).isPresent();
        assertThat(cache.getForUri("file:///Foo.java").get().diagnostics()).hasSize(1);
    }

    @Test
    void update_emptyList_removesEntry() {
        cache.update("file:///Foo.java", List.of(makeDiagnostic(DiagnosticSeverity.Error)));
        cache.update("file:///Foo.java", List.of());
        assertThat(cache.getForUri("file:///Foo.java")).isEmpty();
        assertThat(cache.getLastUpdatedMs()).isPositive();
    }

    @Test
    void update_setsLastUpdatedMs() {
        assertThat(cache.getLastUpdatedMs()).isZero();
        cache.update("file:///Foo.java", List.of(makeDiagnostic(DiagnosticSeverity.Error)));
        assertThat(cache.getLastUpdatedMs()).isPositive();
    }

    @Test
    void clear_emptiesCache() {
        cache.update("file:///Foo.java", List.of(makeDiagnostic(DiagnosticSeverity.Error)));
        cache.clear();
        assertThat(cache.getAll()).isEmpty();
    }

    @Test
    void clear_resetsLastUpdatedMs() {
        cache.update("file:///Foo.java", List.of(makeDiagnostic(DiagnosticSeverity.Error)));
        cache.clear();
        assertThat(cache.getLastUpdatedMs()).isZero();
    }

    @Test
    void getSummary_countsErrorsAndWarnings() {
        cache.update("file:///Foo.java", List.of(
            makeDiagnostic(DiagnosticSeverity.Error),
            makeDiagnostic(DiagnosticSeverity.Error),
            makeDiagnostic(DiagnosticSeverity.Warning)
        ));
        Map<String, DiagnosticsCache.Summary> summary = cache.getSummary();
        assertThat(summary.get("file:///Foo.java").errors()).isEqualTo(2);
        assertThat(summary.get("file:///Foo.java").warnings()).isEqualTo(1);
    }

    @Test
    void getForUri_returnsEmpty_whenNotPresent() {
        assertThat(cache.getForUri("file:///Missing.java")).isEmpty();
    }

    @Test
    void getAll_returnsAllEntries() {
        cache.update("file:///A.java", List.of(makeDiagnostic(DiagnosticSeverity.Error)));
        cache.update("file:///B.java", List.of(makeDiagnostic(DiagnosticSeverity.Warning)));
        assertThat(cache.getAll()).hasSize(2);
    }

    @Test
    void update_concurrentUpdates_noDataRace() throws Exception {
        int threads = 8;
        var latch = new java.util.concurrent.CountDownLatch(threads);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final String uri = "file:///File" + i + ".java";
            executor.submit(() -> {
                cache.update(uri, List.of(makeDiagnostic(DiagnosticSeverity.Error)));
                latch.countDown();
            });
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(cache.getAll()).hasSize(threads);
        assertThat(cache.getLastUpdatedMs()).isPositive();
    }

    private Diagnostic makeDiagnostic(DiagnosticSeverity severity) {
        Diagnostic d = new Diagnostic();
        d.setSeverity(severity);
        d.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        d.setMessage("test");
        return d;
    }
}
