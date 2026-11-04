package io.jenkins.plugins.hygieneauditor.scan;

import static io.jenkins.plugins.hygieneauditor.model.Fixtures.candidate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.hygieneauditor.collector.CancelToken;
import io.jenkins.plugins.hygieneauditor.collector.CollectorResult;
import io.jenkins.plugins.hygieneauditor.collector.HygieneCollector;
import io.jenkins.plugins.hygieneauditor.collector.ScanContext;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.Status;
import io.jenkins.plugins.hygieneauditor.store.FindingStore;
import io.jenkins.plugins.hygieneauditor.store.RetentionPolicy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanRunnerTest {
    @TempDir
    Path dir;

    FindingStore store;
    final AtomicLong nanos = new AtomicLong();
    final Clock clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC);
    ScanRunner runner;

    interface Body {
        CollectorResult run(ScanContext ctx) throws Exception;
    }

    static HygieneCollector collector(String id, Body body) {
        return new HygieneCollector() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Category getCategory() {
                return Category.JOBS;
            }

            @Override
            public String getDisplayName() {
                return id;
            }

            @Override
            public CollectorResult collect(ScanContext ctx) throws InterruptedException {
                try {
                    return body.run(ctx);
                } catch (InterruptedException | RuntimeException | Error e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    @BeforeEach
    void setUp() throws Exception {
        store = FindingStore.open(dir, RetentionPolicy.defaults());
        runner = new ScanRunner(store, clock, nanos::get, (id, n) -> {});
    }

    static CollectorOutcome outcome(ScanRecord r, String id) {
        return r.outcomes().stream()
                .filter(o -> o.collectorId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void happyPathStoresFindings() throws Exception {
        HygieneCollector c = collector("jobs", ctx -> {
            for (int i = 0; i < 3; i++) {
                ctx.checkpoint();
                ctx.emit(candidate("job" + i));
            }
            return CollectorResult.complete();
        });
        ScanRecord r = runner.run("test", List.of(c), ScanSettings.defaults(), new CancelToken());
        assertEquals(ScanRecord.State.COMPLETED, r.state());
        assertEquals(3, outcome(r, "jobs").findings());
        assertEquals(3, outcome(r, "jobs").itemsExamined());
        assertEquals(3, store.all().size());
        assertEquals("jobs", store.all().get(0).collectorId());
    }

    @Test
    void failingCollectorDoesNotStopOthersAndMessageIsRedacted() throws Exception {
        HygieneCollector bad = collector("bad", ctx -> {
            throw new IllegalStateException("boom password=hunter2");
        });
        HygieneCollector linkage = collector("linkage", ctx -> {
            throw new NoClassDefFoundError("some/optional/Plugin");
        });
        HygieneCollector good = collector("good", ctx -> {
            ctx.emit(candidate("x"));
            return CollectorResult.complete();
        });
        ScanRecord r = runner.run("test", List.of(bad, linkage, good), ScanSettings.defaults(), new CancelToken());
        assertEquals(CollectorOutcome.Status.FAILED, outcome(r, "bad").status());
        assertFalse(outcome(r, "bad").message().contains("hunter2"));
        assertEquals(CollectorOutcome.Status.FAILED, outcome(r, "linkage").status());
        assertEquals(CollectorOutcome.Status.COMPLETE, outcome(r, "good").status());
        assertEquals(ScanRecord.State.PARTIAL, r.state());
        assertEquals(1, store.all().size());
    }

    @Test
    void itemCapYieldsPartialKeepsFindingsAndDoesNotResolveOldOnes() throws Exception {
        HygieneCollector first = collector("jobs", ctx -> {
            ctx.emit(candidate("old"));
            return CollectorResult.complete();
        });
        runner.run("t", List.of(first), ScanSettings.defaults(), new CancelToken());
        Finding old = store.all().get(0);

        HygieneCollector capped = collector("jobs", ctx -> {
            for (int i = 0; i < 100; i++) {
                ctx.checkpoint();
            }
            return CollectorResult.complete();
        });
        ScanSettings tight = new ScanSettings(Set.of(), Duration.ofMinutes(1), 5, 10);
        ScanRecord r = runner.run("t", List.of(capped), tight, new CancelToken());
        assertEquals(CollectorOutcome.Status.PARTIAL, outcome(r, "jobs").status());
        assertTrue(outcome(r, "jobs").message().contains("Item limit"));
        assertEquals(Status.OPEN, store.get(old.id()).orElseThrow().status(), "unseen but not resolved");
    }

    @Test
    void timeCapYieldsPartial() throws Exception {
        HygieneCollector slow = collector("jobs", ctx -> {
            for (int i = 0; i < 10; i++) {
                nanos.addAndGet(Duration.ofSeconds(1).toNanos()); // fake clock advances
                ctx.checkpoint();
            }
            return CollectorResult.complete();
        });
        ScanSettings s = new ScanSettings(Set.of(), Duration.ofSeconds(3), 1000, 10);
        ScanRecord r = runner.run("t", List.of(slow), s, new CancelToken());
        assertEquals(CollectorOutcome.Status.PARTIAL, outcome(r, "jobs").status());
        assertTrue(outcome(r, "jobs").message().contains("Time limit"));
    }

    @Test
    void findingCapYieldsPartial() throws Exception {
        HygieneCollector many = collector("jobs", ctx -> {
            for (int i = 0; i < 50; i++) {
                ctx.emit(candidate("j" + i));
            }
            return CollectorResult.complete();
        });
        ScanSettings s = new ScanSettings(Set.of(), Duration.ofMinutes(1), 1000, 10);
        ScanRecord r = runner.run("t", List.of(many), s, new CancelToken());
        assertEquals(10, outcome(r, "jobs").findings());
        assertEquals(CollectorOutcome.Status.PARTIAL, outcome(r, "jobs").status());
        assertEquals(10, store.all().size());
    }

    @Test
    void collectorReportedPartialDoesNotResolve() throws Exception {
        runner.run(
                "t",
                List.of(collector("jobs", ctx -> {
                    ctx.emit(candidate("a"));
                    return CollectorResult.complete();
                })),
                ScanSettings.defaults(),
                new CancelToken());
        runner.run(
                "t",
                List.of(collector("jobs", ctx -> CollectorResult.partial("folder unreadable"))),
                ScanSettings.defaults(),
                new CancelToken());
        assertEquals(Status.OPEN, store.all().get(0).status());
        runner.run(
                "t",
                List.of(collector("jobs", ctx -> CollectorResult.complete())),
                ScanSettings.defaults(),
                new CancelToken());
        assertEquals(Status.RESOLVED, store.all().get(0).status());
    }

    @Test
    void cancelStopsRemainingCollectorsButKeepsEarlierResults() throws Exception {
        CancelToken cancel = new CancelToken();
        HygieneCollector one = collector("one", ctx -> {
            ctx.emit(candidate("a"));
            return CollectorResult.complete();
        });
        HygieneCollector two = collector("two", ctx -> {
            cancel.cancel();
            ctx.checkpoint(); // observes cancellation
            return CollectorResult.complete();
        });
        HygieneCollector three = collector("three", ctx -> {
            throw new AssertionError("must not run");
        });
        ScanRecord r = runner.run("t", List.of(one, two, three), ScanSettings.defaults(), cancel);
        assertEquals(ScanRecord.State.CANCELLED, r.state());
        assertEquals(CollectorOutcome.Status.COMPLETE, outcome(r, "one").status());
        assertEquals(CollectorOutcome.Status.CANCELLED, outcome(r, "two").status());
        assertEquals(CollectorOutcome.Status.CANCELLED, outcome(r, "three").status());
        assertEquals(1, store.all().size());
    }

    @Test
    void threadInterruptIsTreatedAsCancelAndFlagRestored() throws Exception {
        HygieneCollector c = collector("jobs", ctx -> {
            Thread.currentThread().interrupt();
            ctx.checkpoint();
            return CollectorResult.complete();
        });
        ScanRecord r = runner.run("t", List.of(c), ScanSettings.defaults(), new CancelToken());
        try {
            assertEquals(ScanRecord.State.CANCELLED, r.state());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // clear for other tests
        }
    }

    @Test
    void disabledAndNotApplicableAreSkipped() throws Exception {
        HygieneCollector na = new HygieneCollector() {
            public String getId() {
                return "na";
            }

            public Category getCategory() {
                return Category.PLUGINS;
            }

            public String getDisplayName() {
                return "na";
            }

            public boolean isApplicable() {
                return false;
            }

            public CollectorResult collect(ScanContext ctx) {
                throw new AssertionError();
            }
        };
        HygieneCollector off = collector("off", ctx -> {
            throw new AssertionError("must not run");
        });
        ScanSettings only = new ScanSettings(Set.of("na"), Duration.ofMinutes(1), 10, 10);
        ScanRecord r = runner.run("t", List.of(na, off), only, new CancelToken());
        assertEquals(CollectorOutcome.Status.SKIPPED, outcome(r, "na").status());
        assertEquals(CollectorOutcome.Status.SKIPPED, outcome(r, "off").status());
        assertEquals(ScanRecord.State.COMPLETED, r.state());
        assertNotNull(r.scanId());
    }

    @Test
    void progressIsReported() throws Exception {
        List<Long> seen = new ArrayList<>();
        ScanRunner r = new ScanRunner(store, clock, nanos::get, (id, n) -> seen.add(n));
        r.run(
                "t",
                List.of(collector("jobs", ctx -> {
                    for (int i = 0; i < 250; i++) {
                        ctx.checkpoint();
                    }
                    return CollectorResult.complete();
                })),
                ScanSettings.defaults(),
                new CancelToken());
        assertEquals(List.of(100L, 200L), seen);
    }
}
