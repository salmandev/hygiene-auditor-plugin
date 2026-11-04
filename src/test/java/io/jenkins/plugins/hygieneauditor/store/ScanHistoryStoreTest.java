package io.jenkins.plugins.hygieneauditor.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.hygieneauditor.ui.TrendChart;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanHistoryStoreTest {
    @TempDir
    Path dir;

    static ScanHistoryEntry entry(int n, int total, int high) {
        return new ScanHistoryEntry(
                "scan-" + n,
                "test",
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(86400L * n),
                "COMPLETED",
                Map.of("JOBS", total - 1, "AGENTS", 1),
                Map.of("HIGH", high, "LOW", total - high));
    }

    @Test
    void persistsAndReloads() throws Exception {
        ScanHistoryStore s = ScanHistoryStore.open(dir);
        s.append(entry(1, 5, 1));
        s.append(entry(2, 7, 2));
        ScanHistoryStore again = ScanHistoryStore.open(dir);
        assertEquals(2, again.all().size());
        assertEquals(7, again.all().get(1).totalOpen());
        assertEquals(2, again.all().get(1).openBySeverity().get("HIGH"));
    }

    @Test
    void collectorSummariesSurviveAReloadAndOlderFilesWithoutThemStillLoad() throws Exception {
        ScanHistoryStore s = ScanHistoryStore.open(dir);
        s.append(new ScanHistoryEntry(
                "with",
                "manual",
                Instant.parse("2026-01-01T00:00:00Z"),
                "COMPLETED",
                Map.of("JOBS", 1),
                Map.of("LOW", 1),
                List.of(
                        new CollectorSummary("jobs", "COMPLETE", 42, 1),
                        new CollectorSummary("agents", "PARTIAL", 3, 0))));
        var again = ScanHistoryStore.open(dir).all().get(0);
        assertEquals(
                List.of(
                        new CollectorSummary("jobs", "COMPLETE", 42, 1),
                        new CollectorSummary("agents", "PARTIAL", 3, 0)),
                again.collectors());

        // a file written before this field existed
        Files.writeString(
                dir.resolve(ScanHistoryStore.FILE_NAME),
                "{\"schemaVersion\":1,\"scans\":[{\"scanId\":\"old\",\"trigger\":\"scheduled\",\"end\":\"2025-12-01T00:00:00Z\","
                        + "\"state\":\"COMPLETED\",\"openByCategory\":{\"JOBS\":2},\"openBySeverity\":{\"LOW\":2}}]}");
        var old = ScanHistoryStore.open(dir).all();
        assertEquals(1, old.size());
        assertTrue(old.get(0).collectors().isEmpty());
        assertEquals(2, old.get(0).totalOpen());
    }

    @Test
    void isBoundedKeepingTheNewest() throws Exception {
        ScanHistoryStore s = ScanHistoryStore.open(dir);
        for (int i = 0; i < ScanHistoryStore.MAX_ENTRIES + 25; i++) {
            s.append(entry(i, 2, 0));
        }
        assertEquals(ScanHistoryStore.MAX_ENTRIES, s.all().size());
        assertEquals(
                "scan-" + (ScanHistoryStore.MAX_ENTRIES + 24),
                s.all().get(s.all().size() - 1).scanId());
        assertEquals("scan-25", s.all().get(0).scanId());
    }

    @Test
    void corruptFileIsQuarantinedAndNewerSchemaRefused() throws Exception {
        Files.writeString(dir.resolve(ScanHistoryStore.FILE_NAME), "not json {");
        assertTrue(ScanHistoryStore.open(dir).all().isEmpty());
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")));
        }
        Path f = dir.resolve(ScanHistoryStore.FILE_NAME);
        Files.writeString(f, "{\"schemaVersion\":99,\"scans\":[]}");
        assertThrows(UnsupportedSchemaException.class, () -> ScanHistoryStore.open(dir));
        assertEquals("{\"schemaVersion\":99,\"scans\":[]}", Files.readString(f), "left untouched");
    }

    @Test
    void trendChartNeedsTwoPointsAndIsDescribedForScreenReaders() {
        assertEquals("", TrendChart.render(List.of()));
        assertEquals("", TrendChart.render(List.of(entry(1, 3, 0))));
        String svg = TrendChart.render(List.of(entry(1, 3, 0), entry(2, 9, 4), entry(3, 6, 2)));
        assertTrue(svg.startsWith("<svg"));
        assertTrue(svg.contains("role=\"img\""));
        assertTrue(svg.contains("<title"));
        assertTrue(svg.contains("<desc"));
        assertTrue(svg.contains("3 scans, latest 6 open, 2 of them high severity"), svg);
        assertEquals(2, svg.split("<polyline", -1).length - 1);
        assertTrue(svg.contains("2026-01-02") && svg.contains("2026-01-04"));
        assertTrue(!svg.contains("<script") && !svg.contains("javascript:"));
    }

    @Test
    void chartHandlesAllZeroSeries() {
        String svg = TrendChart.render(List.of(entry(1, 0, 0), entry(2, 0, 0)));
        assertTrue(svg.contains("polyline"));
        assertTrue(!svg.contains("NaN") && !svg.contains("Infinity"), svg);
    }
}
