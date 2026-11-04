package io.jenkins.plugins.hygieneauditor.model;

import static io.jenkins.plugins.hygieneauditor.model.Fixtures.T0;
import static io.jenkins.plugins.hygieneauditor.model.Fixtures.candidate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.hygieneauditor.collector.ConfidenceScorer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelTest {
    @Test
    void idIsStableAndDependsOnTypeKindAndName() {
        String a = FindingIds.of("job.stale", "job", "folder/a");
        assertEquals(a, FindingIds.of("job.stale", "job", "folder/a"));
        assertEquals(32, a.length());
        assertNotEquals(a, FindingIds.of("job.disabled", "job", "folder/a"));
        assertNotEquals(a, FindingIds.of("job.stale", "agent", "folder/a"));
        assertNotEquals(a, FindingIds.of("job.stale", "job", "folder/b"));
        // Pin the algorithm: changing it would orphan every stored finding and ignore rule.
        assertEquals("986440435915ec14b938c9f8f934a062", a);
    }

    @Test
    void invalidTypeAndKindAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Subject("Job", "x", null));
        assertThrows(IllegalArgumentException.class, () -> new Subject("job", " ", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FindingCandidate(
                        "NotDotted",
                        Category.JOBS,
                        Subject.of("job", "x", null),
                        Severity.LOW,
                        Confidence.LOW,
                        List.of(),
                        ""));
    }

    @Test
    void evidenceIsRedactedTruncatedAndClamped() {
        Evidence e = new Evidence("src", "password=hunter2 " + "y".repeat(2000), 5000);
        assertFalse(e.text().contains("hunter2"));
        assertTrue(e.text().length() <= Evidence.MAX_TEXT);
        assertEquals(100, e.weight());
    }

    @Test
    void evidenceListIsCapped() {
        List<Evidence> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add(new Evidence("s", "e" + i, 1));
        }
        FindingCandidate c = new FindingCandidate(
                "job.stale", Category.JOBS, Subject.of("job", "x", null), Severity.LOW, Confidence.LOW, many, "");
        assertEquals(
                Finding.MAX_EVIDENCE, Finding.observed(c, "jobs", T0).evidence().size());
    }

    @Test
    void mergeKeepsFirstSeenAndUserDecisionButRefreshesAnalysis() {
        Finding stored = Finding.observed(candidate("a"), "jobs", T0)
                .withStatus(Status.ACKNOWLEDGED, "known", null, "alice", T0);
        Instant t1 = T0.plusSeconds(3600);
        Finding merged = stored.mergeObservation(Finding.observed(candidate("a"), "jobs", t1), t1);
        assertEquals(T0, merged.firstSeen());
        assertEquals(t1, merged.lastSeen());
        assertEquals(Status.ACKNOWLEDGED, merged.status());
        assertEquals("alice", merged.statusChangedBy());
    }

    @Test
    void expiredSnoozeReopens() {
        Instant snooze = T0.plusSeconds(100);
        Finding ignored =
                Finding.observed(candidate("a"), "jobs", T0).withStatus(Status.IGNORED, "later", snooze, "bob", T0);
        Finding still = ignored.mergeObservation(ignored, T0.plusSeconds(50));
        assertEquals(Status.IGNORED, still.status());
        Finding reopened = ignored.mergeObservation(ignored, T0.plusSeconds(200));
        assertEquals(Status.OPEN, reopened.status());
        assertEquals(null, reopened.snoozeUntil());
    }

    @Test
    void confidenceScoringAndCaveats() {
        List<Evidence> strong = List.of(new Evidence("a", "x", 50), new Evidence("b", "y", 30));
        assertEquals(Confidence.HIGH, ConfidenceScorer.score(strong, 0));
        assertEquals(Confidence.MEDIUM, ConfidenceScorer.score(strong, 1));
        assertEquals(Confidence.LOW, ConfidenceScorer.score(strong, 5));
        assertEquals(Confidence.MEDIUM, ConfidenceScorer.score(List.of(new Evidence("a", "x", 40)), 0));
        assertEquals(Confidence.LOW, ConfidenceScorer.score(List.of(), 0));
        // Contradicting evidence lowers the sum.
        assertEquals(
                Confidence.LOW,
                ConfidenceScorer.score(List.of(new Evidence("a", "x", 80), new Evidence("b", "y", -60)), 0));
    }
}
