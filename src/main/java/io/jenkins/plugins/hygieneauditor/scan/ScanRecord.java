package io.jenkins.plugins.hygieneauditor.scan;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.store.RecordResult;
import java.time.Instant;
import java.util.List;

/** Summary of one scan run (persisted history arrives in v0.3). */
public record ScanRecord(
        @NonNull String scanId,
        @NonNull String trigger,
        @NonNull Instant start,
        @NonNull Instant end,
        @NonNull State state,
        @NonNull List<CollectorOutcome> outcomes,
        @NonNull RecordResult changes,
        int exemptedByPolicy) {

    public enum State {
        COMPLETED,
        PARTIAL,
        CANCELLED
    }

    public ScanRecord {
        outcomes = List.copyOf(outcomes);
    }
}
