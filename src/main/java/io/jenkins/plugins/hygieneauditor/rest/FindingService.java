package io.jenkins.plugins.hygieneauditor.rest;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Status;
import io.jenkins.plugins.hygieneauditor.security.FindingAccess;
import io.jenkins.plugins.hygieneauditor.store.FindingStoreHolder;
import java.io.IOException;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read side. Every method returns only what the current user may see. */
public final class FindingService {
    private FindingService() {}

    public record Page(List<Finding> items, int total) {}

    /** All findings visible to the current user, any status. */
    @NonNull
    public static List<Finding> visible() throws IOException {
        return FindingAccess.filter(FindingStoreHolder.get().store().all());
    }

    @NonNull
    public static Optional<Finding> visible(@NonNull String id) throws IOException {
        return FindingStoreHolder.get().store().get(id).filter(FindingAccess::canView);
    }

    @NonNull
    public static Page query(@NonNull FindingQuery q) throws IOException {
        List<Finding> matching = visible().stream()
                .filter(q::matches)
                .sorted(Comparator.comparing(Finding::severity)
                        .reversed()
                        .thenComparing(Comparator.comparing(Finding::confidence).reversed())
                        .thenComparing(Comparator.comparing(Finding::lastSeen).reversed())
                        .thenComparing(Finding::id))
                .toList();
        int from = Math.min(q.offset(), matching.size());
        int to = Math.min(from + q.limit(), matching.size());
        return new Page(matching.subList(from, to), matching.size());
    }

    /** How many findings each category has under the other filters; drives the count on each category pill. */
    @NonNull
    public static Map<Category, Integer> categoryCounts(@NonNull FindingQuery q) throws IOException {
        FindingQuery anyCategory = new FindingQuery(
                java.util.Set.of(), q.statuses(), q.minConfidence(), q.minSeverity(), q.folder(), q.limit(), 0);
        Map<Category, Integer> out = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            out.put(c, 0);
        }
        for (Finding f : visible()) {
            if (anyCategory.matches(f)) {
                out.merge(f.category(), 1, Integer::sum);
            }
        }
        return out;
    }

    /** Counts of OPEN findings, grouped. Used by the summary endpoint, the dashboard and the pipeline step. */
    public record Counts(
            Map<Category, Integer> byCategory,
            Map<Severity, Integer> bySeverity,
            Map<Confidence, Integer> byConfidence,
            Map<Status, Integer> byStatus) {}

    @NonNull
    public static Counts counts(@NonNull List<Finding> visible) {
        Map<Category, Integer> cat = new EnumMap<>(Category.class);
        Map<Severity, Integer> sev = new EnumMap<>(Severity.class);
        Map<Confidence, Integer> conf = new EnumMap<>(Confidence.class);
        Map<Status, Integer> st = new EnumMap<>(Status.class);
        for (Category c : Category.values()) {
            cat.put(c, 0);
        }
        for (Severity s : Severity.values()) {
            sev.put(s, 0);
        }
        for (Confidence c : Confidence.values()) {
            conf.put(c, 0);
        }
        for (Status s : Status.values()) {
            st.put(s, 0);
        }
        for (Finding f : visible) {
            st.merge(f.status(), 1, Integer::sum);
            if (f.status() == Status.OPEN) {
                cat.merge(f.category(), 1, Integer::sum);
                sev.merge(f.severity(), 1, Integer::sum);
                conf.merge(f.confidence(), 1, Integer::sum);
            }
        }
        return new Counts(cat, sev, conf, st);
    }
}
