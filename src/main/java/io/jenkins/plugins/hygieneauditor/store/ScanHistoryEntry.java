package io.jenkins.plugins.hygieneauditor.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregate numbers from one scan, kept to draw the trend chart and the status header. Contains no finding details.
 *
 * @param collectors per-collector results; empty for history written by earlier versions
 */
public record ScanHistoryEntry(
        @NonNull String scanId,
        @NonNull String trigger,
        @NonNull Instant end,
        @NonNull String state,
        @NonNull Map<String, Integer> openByCategory,
        @NonNull Map<String, Integer> openBySeverity,
        @NonNull List<CollectorSummary> collectors) {

    public ScanHistoryEntry {
        openByCategory = Map.copyOf(openByCategory);
        openBySeverity = Map.copyOf(openBySeverity);
        collectors = List.copyOf(collectors);
    }

    /** Without collector detail (history from earlier versions, and tests that do not care). */
    public ScanHistoryEntry(
            @NonNull String scanId,
            @NonNull String trigger,
            @NonNull Instant end,
            @NonNull String state,
            @NonNull Map<String, Integer> openByCategory,
            @NonNull Map<String, Integer> openBySeverity) {
        this(scanId, trigger, end, state, openByCategory, openBySeverity, List.of());
    }

    public int totalOpen() {
        return openByCategory.values().stream().mapToInt(Integer::intValue).sum();
    }
}
