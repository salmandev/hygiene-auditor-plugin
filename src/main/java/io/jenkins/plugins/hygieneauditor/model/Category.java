package io.jenkins.plugins.hygieneauditor.model;

/** Top-level grouping of findings. Names are part of the persisted/REST format: never rename. */
public enum Category {
    JOBS,
    AGENTS,
    CREDENTIALS,
    SHARED_LIBRARIES,
    PLUGINS
}
