package io.jenkins.plugins.hygieneauditor.store;

/** Bounds on stored data. Defaults: 50k findings, resolved findings kept 30 days. */
public record RetentionPolicy(int maxFindings, int resolvedRetentionDays) {
    public RetentionPolicy {
        if (maxFindings < 1 || resolvedRetentionDays < 0) {
            throw new IllegalArgumentException("Invalid retention policy");
        }
    }

    public static RetentionPolicy defaults() {
        return new RetentionPolicy(50_000, 30);
    }
}
