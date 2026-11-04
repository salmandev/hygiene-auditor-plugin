package io.jenkins.plugins.hygieneauditor.collector;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;

/** Everything a collector may touch during a run. Created by the scan engine, one per collector. */
public final class ScanContext {
    private static final int PROGRESS_EVERY = 100;

    private final String collectorId;
    private final Budget budget;
    private final Clock clock;
    private final ProgressSink progress;
    private final Consumer<Finding> sink;

    public ScanContext(
            @NonNull String collectorId,
            @NonNull Budget budget,
            @NonNull Clock clock,
            @NonNull ProgressSink progress,
            @NonNull Consumer<Finding> sink) {
        this.collectorId = Objects.requireNonNull(collectorId);
        this.budget = Objects.requireNonNull(budget);
        this.clock = Objects.requireNonNull(clock);
        this.progress = Objects.requireNonNull(progress);
        this.sink = Objects.requireNonNull(sink);
    }

    /** Call once per examined item. Throws {@link InterruptedException} on cancel, {@link BudgetExceededException} at a cap. */
    public void checkpoint() throws InterruptedException {
        budget.checkpoint();
        if (budget.itemsExamined() % PROGRESS_EVERY == 0) {
            progress.update(collectorId, budget.itemsExamined());
            budget.pause();
        }
    }

    /** Reports a finding. May throw {@link BudgetExceededException} once the per-collector finding cap is hit. */
    public void emit(@NonNull FindingCandidate candidate) {
        budget.onFinding();
        sink.accept(Finding.observed(candidate, collectorId, clock.instant()));
    }

    @NonNull
    public Clock clock() {
        return clock;
    }

    public long itemsExamined() {
        return budget.itemsExamined();
    }
}
