package io.jenkins.plugins.hygieneauditor.collector;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Per-collector resource cap plus cancellation. Not thread-safe: one budget belongs to one collector run on one
 * thread. Collectors call {@link #checkpoint()} once per examined item.
 */
public final class Budget {
    private final CancelToken cancel;
    private final LongSupplier nanoClock;
    private final long startNanos;
    private final long maxNanos;
    private final long maxItems;
    private final int maxFindings;
    private final long pauseMillis;
    private long items;
    private int findings;

    public Budget(
            @NonNull Duration maxDuration,
            long maxItems,
            int maxFindings,
            @NonNull CancelToken cancel,
            @NonNull LongSupplier nanoClock) {
        this(maxDuration, maxItems, maxFindings, cancel, nanoClock, Duration.ZERO);
    }

    public Budget(
            @NonNull Duration maxDuration,
            long maxItems,
            int maxFindings,
            @NonNull CancelToken cancel,
            @NonNull LongSupplier nanoClock,
            @NonNull Duration pause) {
        this.pauseMillis = pause.toMillis();
        this.cancel = Objects.requireNonNull(cancel);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.startNanos = nanoClock.getAsLong();
        this.maxNanos = maxDuration.toNanos();
        this.maxItems = maxItems;
        this.maxFindings = maxFindings;
    }

    /** Counts one examined item; throws if cancelled/interrupted or over a limit. */
    public void checkpoint() throws InterruptedException {
        if (cancel.isCancelled() || Thread.interrupted()) {
            throw new InterruptedException("Scan cancelled");
        }
        items++;
        if (items > maxItems) {
            throw new BudgetExceededException("Item limit reached (" + maxItems + ")");
        }
        if (nanoClock.getAsLong() - startNanos > maxNanos) {
            throw new BudgetExceededException(
                    "Time limit reached (" + Duration.ofNanos(maxNanos).toSeconds() + "s)");
        }
    }

    /** Yields the CPU briefly so a scan never competes with builds. Interruptible (cancel). */
    void pause() throws InterruptedException {
        if (pauseMillis > 0) {
            Thread.sleep(pauseMillis);
        }
    }

    void onFinding() {
        if (findings >= maxFindings) {
            throw new BudgetExceededException("Finding limit reached (" + maxFindings + ")");
        }
        findings++;
    }

    public long itemsExamined() {
        return items;
    }

    public int findingsEmitted() {
        return findings;
    }
}
