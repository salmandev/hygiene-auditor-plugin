package io.jenkins.plugins.hygieneauditor.scan;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.collector.Budget;
import io.jenkins.plugins.hygieneauditor.collector.BudgetExceededException;
import io.jenkins.plugins.hygieneauditor.collector.CancelToken;
import io.jenkins.plugins.hygieneauditor.collector.CollectorResult;
import io.jenkins.plugins.hygieneauditor.collector.HygieneCollector;
import io.jenkins.plugins.hygieneauditor.collector.ProgressSink;
import io.jenkins.plugins.hygieneauditor.collector.ScanContext;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.scan.CollectorOutcome.Status;
import io.jenkins.plugins.hygieneauditor.security.Redactor;
import io.jenkins.plugins.hygieneauditor.store.FindingStore;
import io.jenkins.plugins.hygieneauditor.store.RecordResult;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs collectors one after another, each isolated (a failure never stops the others) and capped by a
 * {@link Budget}, then merges the observations into the {@link FindingStore}. Scheduling, threading and priority are
 * the caller's concern (added with the scan service).
 *
 * <p>Auto-resolution rule: only collectors that finished COMPLETE may resolve their unseen findings; a PARTIAL,
 * FAILED or CANCELLED collector might simply not have looked.
 */
public final class ScanRunner {
    private static final Logger LOGGER = Logger.getLogger(ScanRunner.class.getName());

    private final FindingStore store;
    private final Clock clock;
    private final LongSupplier nanoClock;
    private final ProgressSink progress;
    private final java.util.function.Predicate<Finding> exempt;

    public ScanRunner(
            @NonNull FindingStore store,
            @NonNull Clock clock,
            @NonNull LongSupplier nanoClock,
            @NonNull ProgressSink progress) {
        this(store, clock, nanoClock, progress, f -> false);
    }

    /** @param exempt findings matching this (the ignore policy) are dropped before being recorded */
    public ScanRunner(
            @NonNull FindingStore store,
            @NonNull Clock clock,
            @NonNull LongSupplier nanoClock,
            @NonNull ProgressSink progress,
            @NonNull java.util.function.Predicate<Finding> exempt) {
        this.exempt = Objects.requireNonNull(exempt);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.progress = Objects.requireNonNull(progress);
    }

    @NonNull
    public ScanRecord run(
            @NonNull String trigger,
            @NonNull Collection<? extends HygieneCollector> collectors,
            @NonNull ScanSettings settings,
            @NonNull CancelToken cancel)
            throws IOException {
        var start = clock.instant();
        List<Finding> observed = new ArrayList<>();
        Set<String> complete = new HashSet<>();
        List<CollectorOutcome> outcomes = new ArrayList<>();
        boolean cancelled = false;
        int exempted = 0;
        boolean threadInterrupted = false;

        for (HygieneCollector c : collectors) {
            String id = c.getId();
            if (cancelled || cancel.isCancelled()) {
                cancelled = true;
                outcomes.add(new CollectorOutcome(id, Status.CANCELLED, 0, 0, 0, "Scan cancelled"));
                continue;
            }
            if (!settings.isEnabled(id)) {
                outcomes.add(new CollectorOutcome(id, Status.SKIPPED, 0, 0, 0, "Disabled"));
                continue;
            }
            try {
                if (!c.isApplicable()) {
                    outcomes.add(new CollectorOutcome(id, Status.SKIPPED, 0, 0, 0, "Not applicable"));
                    continue;
                }
            } catch (Exception | LinkageError e) {
                outcomes.add(failed(id, e, 0, 0, 0));
                continue;
            }

            Budget budget = new Budget(
                    settings.perCollectorTimeout(),
                    settings.maxItemsPerCollector(),
                    settings.maxFindingsPerCollector(),
                    cancel,
                    nanoClock,
                    settings.pausePer100Items());
            List<Finding> local = new ArrayList<>();
            ScanContext ctx = new ScanContext(id, budget, clock, progress, local::add);
            long t0 = nanoClock.getAsLong();
            Status status;
            String message = null;
            try {
                CollectorResult r = c.collect(ctx);
                if (r.partial()) {
                    status = Status.PARTIAL;
                    message = Redactor.redactAndTruncate(r.message(), 300);
                } else {
                    status = Status.COMPLETE;
                }
            } catch (BudgetExceededException e) {
                status = Status.PARTIAL;
                message = e.getMessage();
            } catch (InterruptedException e) {
                status = Status.CANCELLED;
                message = "Scan cancelled";
                cancelled = true;
                threadInterrupted = !cancel.isCancelled();
            } catch (Exception | LinkageError e) {
                // LinkageError: an optional plugin disappeared or changed under us. Isolate, don't crash the scan.
                status = Status.FAILED;
                message = Redactor.describe(e);
                LOGGER.log(Level.WARNING, "Collector {0} failed: {1}", new Object[] {id, message});
            }
            long millis = (nanoClock.getAsLong() - t0) / 1_000_000L;
            for (Finding f : local) {
                if (exempt.test(f)) {
                    exempted++;
                } else {
                    observed.add(f);
                }
            }
            if (status == Status.COMPLETE) {
                complete.add(id);
            }
            outcomes.add(new CollectorOutcome(id, status, budget.itemsExamined(), local.size(), millis, message));
        }

        // Persist before restoring the interrupt flag: NIO file channels fail on an interrupted thread.
        var end = clock.instant();
        RecordResult changes = store.recordScan(observed, complete, end);
        if (threadInterrupted) {
            Thread.currentThread().interrupt();
        }
        ScanRecord.State state = cancelled
                ? ScanRecord.State.CANCELLED
                : outcomes.stream().allMatch(o -> o.status() == Status.COMPLETE || o.status() == Status.SKIPPED)
                        ? ScanRecord.State.COMPLETED
                        : ScanRecord.State.PARTIAL;
        return new ScanRecord(UUID.randomUUID().toString(), trigger, start, end, state, outcomes, changes, exempted);
    }

    private static CollectorOutcome failed(String id, Throwable e, long items, int findings, long millis) {
        return new CollectorOutcome(id, Status.FAILED, items, findings, millis, Redactor.describe(e));
    }
}
