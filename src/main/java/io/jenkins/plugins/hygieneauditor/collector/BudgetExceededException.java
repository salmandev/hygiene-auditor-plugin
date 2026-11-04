package io.jenkins.plugins.hygieneauditor.collector;

import edu.umd.cs.findbugs.annotations.NonNull;

/** Thrown by {@link Budget} when a collector hits its time, item or finding cap. Result is a PARTIAL outcome. */
public class BudgetExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BudgetExceededException(@NonNull String message) {
        super(message, null, false, false); // no stack trace: this is control flow, not a bug
    }
}
