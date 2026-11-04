package io.jenkins.plugins.hygieneauditor.model;

/** Lifecycle of a finding. RESOLVED is set only by the scan engine, never by users. Persisted by name. */
public enum Status {
    OPEN,
    ACKNOWLEDGED,
    IGNORED,
    RESOLVED
}
