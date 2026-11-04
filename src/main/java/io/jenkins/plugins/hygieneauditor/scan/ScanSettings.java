package io.jenkins.plugins.hygieneauditor.scan;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Set;

/**
 * Per-scan limits.
 *
 * @param enabledCollectorIds empty means "all collectors"
 */
public record ScanSettings(
        @NonNull Set<String> enabledCollectorIds,
        @NonNull Duration perCollectorTimeout,
        long maxItemsPerCollector,
        int maxFindingsPerCollector,
        @NonNull Duration pausePer100Items) {

    public ScanSettings(
            @NonNull Set<String> enabledCollectorIds,
            @NonNull Duration perCollectorTimeout,
            long maxItemsPerCollector,
            int maxFindingsPerCollector) {
        this(enabledCollectorIds, perCollectorTimeout, maxItemsPerCollector, maxFindingsPerCollector, Duration.ZERO);
    }

    public ScanSettings {
        enabledCollectorIds = Set.copyOf(enabledCollectorIds);
        if (perCollectorTimeout.isNegative()
                || perCollectorTimeout.isZero()
                || maxItemsPerCollector < 1
                || maxFindingsPerCollector < 1) {
            throw new IllegalArgumentException("Limits must be positive");
        }
    }

    public static ScanSettings defaults() {
        return new ScanSettings(Set.of(), Duration.ofMinutes(10), 1_000_000L, 10_000, Duration.ofMillis(5));
    }

    public boolean isEnabled(@NonNull String collectorId) {
        return enabledCollectorIds.isEmpty() || enabledCollectorIds.contains(collectorId);
    }
}
