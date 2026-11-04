package io.jenkins.plugins.hygieneauditor.store;

import edu.umd.cs.findbugs.annotations.NonNull;

/** One collector's result in a scan, kept in the history so the dashboard header survives a restart. */
public record CollectorSummary(@NonNull String id, @NonNull String status, long items, int findings) {}
