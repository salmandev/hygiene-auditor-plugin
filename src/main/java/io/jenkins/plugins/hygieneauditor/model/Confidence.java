package io.jenkins.plugins.hygieneauditor.model;

/** How sure the auditor is that the subject is really unused, ordered ascending. Persisted by name. */
public enum Confidence {
    LOW,
    MEDIUM,
    HIGH;

    /** Returns this level lowered by {@code steps} (never below {@link #LOW}). */
    public Confidence downgrade(int steps) {
        return values()[Math.max(0, ordinal() - Math.max(0, steps))];
    }
}
