package io.jenkins.plugins.hygieneauditor.collector;

import edu.umd.cs.findbugs.annotations.NonNull;

/** Receives coarse progress updates for the UI. Called from the scan thread; must be cheap and non-blocking. */
@FunctionalInterface
public interface ProgressSink {
    ProgressSink NONE = (collectorId, items) -> {};

    void update(@NonNull String collectorId, long itemsExamined);
}
