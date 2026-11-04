package io.jenkins.plugins.hygieneauditor.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Status;
import io.jenkins.plugins.hygieneauditor.rest.FindingBean;
import io.jenkins.plugins.hygieneauditor.rest.FindingQuery;
import io.jenkins.plugins.hygieneauditor.rest.FindingService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Everything the findings table and detail panel need; shared by the global dashboard and the per-folder page. */
public final class FindingsPanel {

    /** A category filter pill. {@code url} keeps every other filter. */
    public record Pill(String label, int count, String url, boolean active) {}

    private final FindingQuery query;
    private final FindingService.Page page;
    private final boolean showFolderFilter;
    private final Map<Category, Integer> categoryCounts;

    @CheckForNull
    private final String selectedId;

    @CheckForNull
    private final String statusAction;

    /**
     * @param categoryCounts findings per category under the other filters (for the pills)
     * @param selectedId the finding shown in the side panel; the first row when null or not on this page
     * @param statusAction relative URL of the status-change endpoint for the side panel, or null where not offered
     */
    public FindingsPanel(
            @NonNull FindingQuery query,
            @NonNull FindingService.Page page,
            boolean showFolderFilter,
            @NonNull Map<Category, Integer> categoryCounts,
            @CheckForNull String selectedId,
            @CheckForNull String statusAction) {
        this.query = query;
        this.page = page;
        this.showFolderFilter = showFolderFilter;
        this.categoryCounts = categoryCounts;
        this.selectedId = selectedId;
        this.statusAction = statusAction;
    }

    @NonNull
    public List<FindingBean> getRows() {
        return page.items().stream().map(f -> new FindingBean(f, null)).collect(Collectors.toList());
    }

    /** The finding for the side panel. */
    @CheckForNull
    public FindingBean getSelected() {
        var items = page.items();
        if (items.isEmpty()) {
            return null;
        }
        var chosen = selectedId == null
                ? items.get(0)
                : items.stream()
                        .filter(f -> f.id().equals(selectedId))
                        .findFirst()
                        .orElse(items.get(0));
        return new FindingBean(chosen, statusAction);
    }

    public boolean isSelected(@NonNull FindingBean row) {
        FindingBean s = getSelected();
        return s != null && s.getId().equals(row.getId());
    }

    public int getTotal() {
        return page.total();
    }

    public int getFrom() {
        return page.total() == 0 ? 0 : query.offset() + 1;
    }

    public int getTo() {
        return query.offset() + page.items().size();
    }

    /** True on the Jenkins-wide page, false on a folder page (which has no scan, export or removal-plan routes). */
    public boolean isGlobal() {
        return showFolderFilter;
    }

    public boolean isShowFolderFilter() {
        return showFolderFilter;
    }

    @NonNull
    public FindingQuery getQuery() {
        return query;
    }

    // ---- category pills

    @NonNull
    public List<Pill> getPills() {
        int all = categoryCounts.values().stream().mapToInt(Integer::intValue).sum();
        List<Pill> pills = new ArrayList<>();
        pills.add(new Pill(
                Messages_all(),
                all,
                url(overrides("category", null)),
                query.categories().isEmpty()));
        for (Category c : Category.values()) {
            int n = categoryCounts.getOrDefault(c, 0);
            boolean active = query.categories().contains(c);
            if (n > 0 || active) {
                pills.add(new Pill(Labels.category(c), n, url(overrides("category", c.name())), active));
            }
        }
        return pills;
    }

    private static String Messages_all() {
        return io.jenkins.plugins.hygieneauditor.Messages.Pill_All();
    }

    // ---- current filter values, for the form

    public String getSelectedCategory() {
        return query.categories().size() == 1
                ? query.categories().iterator().next().name()
                : "";
    }

    public String getSelectedStatus() {
        if (query.statuses().size() == Status.values().length) {
            return "all";
        }
        return query.statuses().size() == 1 ? query.statuses().iterator().next().name() : "OPEN";
    }

    public String getSelectedConfidence() {
        Confidence c = query.minConfidence();
        return c == null ? "" : c.name();
    }

    public String getSelectedSeverity() {
        Severity v = query.minSeverity();
        return v == null ? "" : v.name();
    }

    public String getSelectedFolder() {
        String f = query.folder();
        return f == null ? "" : f;
    }

    /** True when something other than the defaults is filtered (keeps the advanced filters open). */
    public boolean isAdvancedActive() {
        return !getSelectedStatus().equals("OPEN")
                || !getSelectedConfidence().isEmpty()
                || !getSelectedSeverity().isEmpty()
                || !getSelectedFolder().isEmpty();
    }

    // ---- option lists as (value, label)

    @NonNull
    public Map<String, String> getStatusOptions() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Status s : Status.values()) {
            m.put(s.name(), Labels.status(s));
        }
        m.put("all", "*");
        return m;
    }

    @NonNull
    public Map<String, String> getConfidenceOptions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("", "");
        for (Confidence c : Confidence.values()) {
            m.put(c.name(), Labels.confidence(c));
        }
        return m;
    }

    @NonNull
    public Map<String, String> getSeverityOptions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("", "");
        for (Severity s : Severity.values()) {
            m.put(s.name(), Labels.severity(s));
        }
        return m;
    }

    // ---- links

    public boolean isHasPrevious() {
        return query.offset() > 0;
    }

    public boolean isHasNext() {
        return query.offset() + query.limit() < page.total();
    }

    @NonNull
    public String getPreviousUrl() {
        return url(overrides("offset", Integer.toString(Math.max(0, query.offset() - query.limit()))));
    }

    @NonNull
    public String getNextUrl() {
        return url(overrides("offset", Integer.toString(query.offset() + query.limit())));
    }

    /** Selecting a row keeps the filters and shows that finding in the side panel. */
    @NonNull
    public String selectUrl(@NonNull FindingBean row) {
        return url(overrides("selected", row.getId()));
    }

    /** The current filter as a query string without paging or selection, for the download links. */
    @NonNull
    public String getExportQuery() {
        Map<String, String> m = current();
        m.remove("limit");
        m.remove("offset");
        m.remove("selected");
        return join(m);
    }

    private Map<String, String> current() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("category", getSelectedCategory());
        m.put("status", getSelectedStatus());
        m.put("minConfidence", getSelectedConfidence());
        m.put("minSeverity", getSelectedSeverity());
        if (showFolderFilter) {
            m.put("folder", getSelectedFolder());
        }
        m.put("limit", Integer.toString(query.limit()));
        m.put("offset", Integer.toString(query.offset()));
        if (selectedId != null) {
            m.put("selected", selectedId);
        }
        return m;
    }

    private static Map<String, String> overrides(String key, @CheckForNull String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key, value);
        if (!key.equals("offset")) {
            m.put("offset", null); // a new filter or selection starts at the first page
        }
        if (key.equals("category")) {
            m.put("selected", null);
        }
        return m;
    }

    private String url(Map<String, String> overrides) {
        Map<String, String> m = current();
        overrides.forEach((k, v) -> {
            if (v == null) {
                m.remove(k);
            } else {
                m.put(k, v);
            }
        });
        return "?" + join(m);
    }

    private static String join(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        });
        return sb.toString();
    }

    /** Lower-case token for CSS classes. */
    public static String css(@NonNull String enumName) {
        return enumName.toLowerCase(Locale.ROOT);
    }
}
