package io.jenkins.plugins.hygieneauditor.collector;

import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * What a collector reports when it returns normally. Report {@link #partial(String)} if you skipped part of the
 * universe on purpose (e.g. an unreadable folder): the engine then will not auto-resolve your older findings.
 */
public record CollectorResult(boolean partial, @CheckForNull String message) {
    public static CollectorResult complete() {
        return new CollectorResult(false, null);
    }

    public static CollectorResult partial(String message) {
        return new CollectorResult(true, message);
    }
}
