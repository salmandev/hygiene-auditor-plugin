package io.jenkins.plugins.hygieneauditor.scan;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Snapshot of the running scan, for the UI/REST. */
public record ScanStatus(
        @NonNull String scanId,
        @NonNull String trigger,
        @NonNull Instant startedAt,
        @NonNull Set<String> collectors,
        @NonNull Map<String, Long> itemsExamined,
        boolean cancelling) {
    public ScanStatus {
        collectors = Set.copyOf(collectors);
        itemsExamined = Map.copyOf(itemsExamined);
    }
}
