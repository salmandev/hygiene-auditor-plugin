package io.jenkins.plugins.hygieneauditor.scan;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/** How one collector fared in one scan. {@code message} is always redacted. */
public record CollectorOutcome(
        @NonNull String collectorId,
        @NonNull Status status,
        long itemsExamined,
        int findings,
        long durationMillis,
        @CheckForNull String message) {

    public enum Status {
        COMPLETE,
        PARTIAL,
        FAILED,
        SKIPPED,
        CANCELLED
    }
}
