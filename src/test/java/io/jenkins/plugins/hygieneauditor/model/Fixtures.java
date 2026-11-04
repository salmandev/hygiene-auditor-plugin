package io.jenkins.plugins.hygieneauditor.model;

import java.time.Instant;
import java.util.List;

/** Shared test data. */
public final class Fixtures {
    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private Fixtures() {}

    public static FindingCandidate candidate(String name) {
        return new FindingCandidate(
                "job.stale",
                Category.JOBS,
                Subject.of("job", name, null),
                Severity.LOW,
                Confidence.MEDIUM,
                List.of(new Evidence("test", "never built", 50)),
                "Review job");
    }
}
