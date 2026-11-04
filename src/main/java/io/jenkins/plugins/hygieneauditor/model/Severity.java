package io.jenkins.plugins.hygieneauditor.model;

/** How much attention a finding deserves, ordered ascending. Persisted by name. */
public enum Severity {
    INFO,
    LOW,
    MEDIUM,
    HIGH
}
