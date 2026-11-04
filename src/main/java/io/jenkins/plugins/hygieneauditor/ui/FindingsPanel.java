package io.jenkins.plugins.hygieneauditor.ui;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Status;
import io.jenkins.plugins.hygieneauditor.rest.FindingBean;
import io.jenkins.plugins.hygieneauditor.rest.FindingQuery;
import io.jenkins.plugins.hygieneauditor.rest.FindingService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Everything the findings table fragment needs; shared by the global dashboard and the per-folder page. */
public final class FindingsPanel {
    private final FindingQuery query;
    private final FindingService.Page page;
    private final boolean showFolderFilter;

    public FindingsPanel(@NonNull FindingQuery query, @NonNull FindingService.Page page, boolean showFolderFilter) {
        this.query = query;
        this.page = page;
        this.showFolderFilter = showFolderFilter;
    }

    @NonNull
    public List<FindingBean> getRows() {
        return page.items().stream().map(f -> new FindingBean(f, null)).collect(Collectors.toList());
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

    public boolean isShowFolderFilter() {
        return showFolderFilter;
    }

    @NonNull
    public FindingQuery getQuery() {
        return query;
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

    // ---- option lists as (value, label)

    @NonNull
    public Map<String, String> getCategoryOptions() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("", "");
        for (Category c : Category.values()) {
            m.put(c.name(), Labels.category(c));
        }
        return m;
    }

    @NonNull
    public Map<String, String> getStatusOptions() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (Status s : Status.values()) {
            m.put(s.name(), Labels.status(s));
        }
        m.put("all", "*");
        return m;
    }

    @NonNull
    public Map<String, String> getConfidenceOptions() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("", "");
        for (Confidence c : Confidence.values()) {
            m.put(c.name(), Labels.confidence(c));
        }
        return m;
    }

    @NonNull
    public Map<String, String> getSeverityOptions() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("", "");
        for (Severity s : Severity.values()) {
            m.put(s.name(), Labels.severity(s));
        }
        return m;
    }

    // ---- paging

    public boolean isHasPrevious() {
        return query.offset() > 0;
    }

    public boolean isHasNext() {
        return query.offset() + query.limit() < page.total();
    }

    @NonNull
    public String getPreviousUrl() {
        return "?" + queryString(Math.max(0, query.offset() - query.limit()));
    }

    @NonNull
    public String getNextUrl() {
        return "?" + queryString(query.offset() + query.limit());
    }

    /** The current filter as a query string without paging, for the download links. */
    @NonNull
    public String getExportQuery() {
        String q = queryString(0);
        return q.replaceAll("(^|&)(limit|offset)=[^&]*", "").replaceFirst("^&", "");
    }

    private String queryString(int offset) {
        StringBuilder sb = new StringBuilder();
        append(sb, "category", getSelectedCategory());
        append(sb, "status", getSelectedStatus());
        append(sb, "minConfidence", getSelectedConfidence());
        append(sb, "minSeverity", getSelectedSeverity());
        if (showFolderFilter) {
            append(sb, "folder", getSelectedFolder());
        }
        append(sb, "limit", Integer.toString(query.limit()));
        append(sb, "offset", Integer.toString(offset));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(key).append('=').append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Lower-case token for CSS classes. */
    public static String css(@NonNull String enumName) {
        return enumName.toLowerCase(Locale.ROOT);
    }
}
