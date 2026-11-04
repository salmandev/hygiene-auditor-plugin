package io.jenkins.plugins.hygieneauditor.rest;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.scan.CollectorOutcome;
import io.jenkins.plugins.hygieneauditor.scan.ScanRecord;
import io.jenkins.plugins.hygieneauditor.scan.ScanService;
import io.jenkins.plugins.hygieneauditor.scan.ScanStatus;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/** Served at {@code api/json}: counts of what the current user may see, plus scan state. */
@ExportedBean(defaultVisibility = 999)
public class SummaryBean {
    private final FindingService.Counts counts;
    private final List<Finding> visible;
    private final Optional<ScanStatus> running;
    private final ScanRecord last;

    public SummaryBean() throws IOException {
        this.visible = FindingService.visible();
        this.counts = FindingService.counts(visible);
        ScanService svc = ScanService.get();
        this.running = svc.status();
        this.last = svc.lastScan();
    }

    /** Open findings of one type (not exported: used by the dashboard cards). */
    public int openOfType(String type) {
        return (int) visible.stream()
                .filter(f -> f.status() == io.jenkins.plugins.hygieneauditor.model.Status.OPEN
                        && f.type().equals(type))
                .count();
    }

    /** Open, high-confidence findings in a category. */
    public int openHighConfidence(io.jenkins.plugins.hygieneauditor.model.Category c) {
        return (int) visible.stream()
                .filter(f -> f.status() == io.jenkins.plugins.hygieneauditor.model.Status.OPEN
                        && f.category() == c
                        && f.confidence() == io.jenkins.plugins.hygieneauditor.model.Confidence.HIGH)
                .count();
    }

    /** Findings that carry a decision: acknowledged, ignored or resolved. */
    public int reviewedCount() {
        var st = counts.byStatus();
        return st.getOrDefault(io.jenkins.plugins.hygieneauditor.model.Status.ACKNOWLEDGED, 0)
                + st.getOrDefault(io.jenkins.plugins.hygieneauditor.model.Status.IGNORED, 0)
                + st.getOrDefault(io.jenkins.plugins.hygieneauditor.model.Status.RESOLVED, 0);
    }

    public int openCount() {
        return counts.byStatus().getOrDefault(io.jenkins.plugins.hygieneauditor.model.Status.OPEN, 0);
    }

    @Exported
    public Map<String, Integer> getOpenByCategory() {
        return names(counts.byCategory());
    }

    @Exported
    public Map<String, Integer> getOpenBySeverity() {
        return names(counts.bySeverity());
    }

    @Exported
    public Map<String, Integer> getOpenByConfidence() {
        return names(counts.byConfidence());
    }

    @Exported
    public Map<String, Integer> getByStatus() {
        return names(counts.byStatus());
    }

    @Exported
    public boolean isScanRunning() {
        return running.isPresent();
    }

    @Exported
    @CheckForNull
    public String getRunningScanId() {
        return running.map(ScanStatus::scanId).orElse(null);
    }

    @Exported
    @CheckForNull
    public String getLastScanState() {
        return last == null ? null : last.state().name();
    }

    @Exported
    @CheckForNull
    public String getLastScanEnd() {
        return last == null ? null : last.end().toString();
    }

    @Exported
    public List<Map<String, String>> getLastScanCollectors() {
        if (last == null) {
            return List.of();
        }
        return last.outcomes().stream().map(SummaryBean::outcome).toList();
    }

    private static Map<String, String> outcome(CollectorOutcome o) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("collector", o.collectorId());
        m.put("status", o.status().name());
        m.put("items", Long.toString(o.itemsExamined()));
        m.put("findings", Integer.toString(o.findings()));
        if (o.message() != null) {
            m.put("message", o.message());
        }
        return m;
    }

    private static <E extends Enum<E>> Map<String, Integer> names(Map<E, Integer> in) {
        Map<String, Integer> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k.name(), v));
        return out;
    }
}
