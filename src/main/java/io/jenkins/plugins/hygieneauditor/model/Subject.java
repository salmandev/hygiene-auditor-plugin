package io.jenkins.plugins.hygieneauditor.model;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a finding is about.
 *
 * @param kind lower-case token such as {@code job}, {@code agent}, {@code credential}, {@code library}
 * @param fullName stable identifier (e.g. Jenkins full name); part of the finding id, so never display-formatted
 * @param displayName for the UI; defaults to fullName
 */
public record Subject(
        @NonNull String kind,
        @NonNull String fullName,
        @NonNull String displayName) {

    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    public static final int MAX_NAME = 1024;

    public Subject {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fullName, "fullName");
        if (!KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException("Invalid subject kind: " + kind);
        }
        if (fullName.isBlank() || fullName.length() > MAX_NAME) {
            throw new IllegalArgumentException("Subject fullName must be 1.." + MAX_NAME + " characters");
        }
        displayName = displayName == null || displayName.isBlank() ? fullName : displayName;
    }

    public static Subject of(@NonNull String kind, @NonNull String fullName, @CheckForNull String displayName) {
        return new Subject(kind, fullName, displayName == null ? fullName : displayName);
    }
}
