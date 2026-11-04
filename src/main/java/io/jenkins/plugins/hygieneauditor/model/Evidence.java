package io.jenkins.plugins.hygieneauditor.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.security.Redactor;
import java.util.Objects;

/**
 * One observation supporting (positive weight), contradicting (negative) or merely describing (zero) a finding.
 * Text is redacted and truncated on construction, so an Evidence can never carry a secret that looks like one.
 *
 * @param source short machine-ish origin such as {@code job-config-scan} or {@code fingerprint}
 * @param text human-readable explanation
 * @param weight -100..100, how strongly this supports "unused"
 */
public record Evidence(@NonNull String source, @NonNull String text, int weight) {

    public static final int MAX_TEXT = 500;
    public static final int MAX_SOURCE = 64;

    public Evidence {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(text, "text");
        source = Redactor.redactAndTruncate(source, MAX_SOURCE);
        text = Redactor.redactAndTruncate(text, MAX_TEXT);
        weight = Math.max(-100, Math.min(100, weight));
    }
}
