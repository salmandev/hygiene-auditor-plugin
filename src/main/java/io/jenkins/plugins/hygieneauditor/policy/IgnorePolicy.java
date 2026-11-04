package io.jenkins.plugins.hygieneauditor.policy;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import java.util.List;

/** An immutable set of {@link IgnoreRule}s. */
public final class IgnorePolicy {
    public static final IgnorePolicy NONE = new IgnorePolicy(List.of());

    private final List<IgnoreRule> rules;

    public IgnorePolicy(@NonNull List<IgnoreRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public boolean isExempt(@NonNull Finding f) {
        for (IgnoreRule r : rules) {
            if (r.matches(f)) {
                return true;
            }
        }
        return false;
    }
}
