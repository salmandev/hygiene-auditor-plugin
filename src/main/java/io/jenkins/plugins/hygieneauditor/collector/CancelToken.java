package io.jenkins.plugins.hygieneauditor.collector;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation flag shared between the scan trigger (UI/step) and the running collectors. */
public final class CancelToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
