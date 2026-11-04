package io.jenkins.plugins.hygieneauditor.rest;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Status;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Filter for the findings list. Request parameters: {@code category} (comma separated), {@code status} (comma
 * separated or {@code all}; default {@code OPEN}), {@code minConfidence}, {@code minSeverity}, {@code folder},
 * {@code limit} (default 500, max 5000), {@code offset}.
 */
public record FindingQuery(
        @NonNull Set<Category> categories,
        @NonNull Set<Status> statuses,
        @CheckForNull Confidence minConfidence,
        @CheckForNull Severity minSeverity,
        @CheckForNull String folder,
        int limit,
        int offset) {

    public static final int DEFAULT_LIMIT = 500;
    public static final int MAX_LIMIT = 5000;

    public FindingQuery {
        categories = Set.copyOf(categories);
        statuses = Set.copyOf(statuses);
        limit = Math.max(1, Math.min(MAX_LIMIT, limit));
        offset = Math.max(0, offset);
    }

    public static FindingQuery all() {
        return new FindingQuery(Set.of(), EnumSet.allOf(Status.class), null, null, null, MAX_LIMIT, 0);
    }

    /** @throws org.kohsuke.stapler.HttpResponses.HttpResponseException (400) on a bad parameter */
    public static FindingQuery fromRequest(@NonNull StaplerRequest2 req) {
        try {
            Set<Category> cats = EnumSet.noneOf(Category.class);
            for (String v : csv(req.getParameterValues("category"))) {
                cats.add(Category.valueOf(v.toUpperCase(Locale.ROOT)));
            }
            Set<Status> statuses = EnumSet.noneOf(Status.class);
            String[] raw = req.getParameterValues("status");
            if (raw == null) {
                statuses.add(Status.OPEN);
            } else {
                for (String v : csv(raw)) {
                    if (v.equalsIgnoreCase("all")) {
                        statuses.addAll(EnumSet.allOf(Status.class));
                    } else {
                        statuses.add(Status.valueOf(v.toUpperCase(Locale.ROOT)));
                    }
                }
            }
            String mc = req.getParameter("minConfidence");
            String ms = req.getParameter("minSeverity");
            String folder = req.getParameter("folder");
            return new FindingQuery(
                    cats,
                    statuses,
                    mc == null || mc.isBlank()
                            ? null
                            : Confidence.valueOf(mc.trim().toUpperCase(Locale.ROOT)),
                    ms == null || ms.isBlank()
                            ? null
                            : Severity.valueOf(ms.trim().toUpperCase(Locale.ROOT)),
                    folder == null || folder.isBlank() ? null : folder.trim(),
                    intParam(req.getParameter("limit"), DEFAULT_LIMIT),
                    intParam(req.getParameter("offset"), 0));
        } catch (IllegalArgumentException e) {
            throw HttpResponses.error(400, "Invalid query parameter: " + e.getMessage());
        }
    }

    /** Same filter, confined to a folder subtree (used by the per-folder page, which ignores a client-supplied folder). */
    public FindingQuery withFolder(@NonNull String folderPath) {
        return new FindingQuery(categories, statuses, minConfidence, minSeverity, folderPath, limit, offset);
    }

    /** Same filter with a different minimum confidence (the removal plan defaults to HIGH). */
    public FindingQuery withMinConfidence(@NonNull Confidence c) {
        return new FindingQuery(categories, statuses, c, minSeverity, folder, limit, offset);
    }

    public boolean matches(@NonNull Finding f) {
        if (!categories.isEmpty() && !categories.contains(f.category())) {
            return false;
        }
        if (!statuses.contains(f.status())) {
            return false;
        }
        if (minConfidence != null && f.confidence().compareTo(minConfidence) < 0) {
            return false;
        }
        if (minSeverity != null && f.severity().compareTo(minSeverity) < 0) {
            return false;
        }
        return folder == null || inFolder(f, folder);
    }

    private static boolean inFolder(Finding f, String folder) {
        return io.jenkins.plugins.hygieneauditor.policy.SubjectPaths.inFolder(f, folder);
    }

    private static Iterable<String> csv(String[] values) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (values != null) {
            for (String v : values) {
                for (String part : v.split(",")) {
                    if (!part.isBlank()) {
                        out.add(part.trim());
                    }
                }
            }
        }
        return out;
    }

    private static int intParam(String v, int dflt) {
        if (v == null || v.isBlank()) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + v);
        }
    }
}
