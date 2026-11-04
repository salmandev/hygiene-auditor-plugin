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
    private final Optional<ScanStatus> running;
    private final ScanRecord last;

    public SummaryBean() throws IOException {
        List<Finding> visible = FindingService.visible();
        this.counts = FindingService.counts(visible);
        ScanService svc = ScanService.get();
        this.running = svc.status();
        this.last = svc.lastScan();
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
