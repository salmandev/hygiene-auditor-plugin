package io.jenkins.plugins.hygieneauditor.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Map;

/** Aggregate numbers from one scan, kept to draw the trend chart. Contains no finding details. */
public record ScanHistoryEntry(
        @NonNull String scanId,
        @NonNull String trigger,
        @NonNull Instant end,
        @NonNull String state,
        @NonNull Map<String, Integer> openByCategory,
        @NonNull Map<String, Integer> openBySeverity) {

    public ScanHistoryEntry {
        openByCategory = Map.copyOf(openByCategory);
        openBySeverity = Map.copyOf(openBySeverity);
    }

    public int totalOpen() {
        return openByCategory.values().stream().mapToInt(Integer::intValue).sum();
    }
}
