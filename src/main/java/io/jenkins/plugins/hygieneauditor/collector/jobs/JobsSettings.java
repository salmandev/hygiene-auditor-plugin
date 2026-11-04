package io.jenkins.plugins.hygieneauditor.collector.jobs;

/**
 * Thresholds for the jobs collector.
 *
 * @param staleDays a job whose last build is older than this is stale
 * @param neverBuiltGraceDays never-built jobs younger than this (by config age) are not reported
 * @param disabledDays a job disabled (config unchanged) for longer than this is reported
 */
public record JobsSettings(int staleDays, int neverBuiltGraceDays, int disabledDays) {
    public JobsSettings {
        if (staleDays < 1 || neverBuiltGraceDays < 0 || disabledDays < 1) {
            throw new IllegalArgumentException("Invalid thresholds");
        }
    }

    public static JobsSettings defaults() {
        return new JobsSettings(365, 30, 180);
    }
}
