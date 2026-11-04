package io.jenkins.plugins.hygieneauditor.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.security.Redactor;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a collector emits. The scan engine turns it into a {@link Finding} (adding id, collector id, timestamps).
 *
 * @param type stable dotted token, e.g. {@code job.stale}; part of the public contract
 */
public record FindingCandidate(
        @NonNull String type,
        @NonNull Category category,
        @NonNull Subject subject,
        @NonNull Severity severity,
        @NonNull Confidence confidence,
        @NonNull List<Evidence> evidence,
        @NonNull String suggestedAction) {

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+");

    public FindingCandidate {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(suggestedAction, "suggestedAction");
        if (!TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("Invalid finding type: " + type);
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        suggestedAction = Redactor.redactAndTruncate(suggestedAction, 500);
    }
}
