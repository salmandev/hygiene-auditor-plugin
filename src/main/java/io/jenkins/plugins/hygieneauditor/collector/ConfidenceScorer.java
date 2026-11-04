package io.jenkins.plugins.hygieneauditor.collector;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import java.util.List;

/**
 * Turns evidence weights into a confidence level. Confidence is computed, never hand-assigned, so that caveats
 * (dynamic references, disabled tracking, partial scans) reliably lower it.
 *
 * <p>Sum of weights: {@code >= 70} HIGH, {@code >= 40} MEDIUM, otherwise LOW; then lowered by one level per caveat.
 */
public final class ConfidenceScorer {
    public static final int HIGH_THRESHOLD = 70;
    public static final int MEDIUM_THRESHOLD = 40;

    private ConfidenceScorer() {}

    @NonNull
    public static Confidence score(@NonNull List<Evidence> evidence, int caveats) {
        int sum = evidence.stream().mapToInt(Evidence::weight).sum();
        Confidence base =
                sum >= HIGH_THRESHOLD ? Confidence.HIGH : sum >= MEDIUM_THRESHOLD ? Confidence.MEDIUM : Confidence.LOW;
        return base.downgrade(caveats);
    }
}
