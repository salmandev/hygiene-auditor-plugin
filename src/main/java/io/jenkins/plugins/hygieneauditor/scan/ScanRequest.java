package io.jenkins.plugins.hygieneauditor.scan;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Category;
import java.util.Set;

/**
 * @param categories categories to scan; empty means all
 * @param trigger free-text origin for logs and history: {@code scheduled}, {@code manual}, {@code pipeline}
 */
public record ScanRequest(
        @NonNull Set<Category> categories, @NonNull String trigger) {
    public ScanRequest {
        categories = Set.copyOf(categories);
    }
}
