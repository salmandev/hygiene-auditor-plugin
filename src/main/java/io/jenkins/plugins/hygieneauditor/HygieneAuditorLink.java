package io.jenkins.plugins.hygieneauditor;

import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Api;
import hudson.model.ManagementLink;
import hudson.security.Permission;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.Status;
import io.jenkins.plugins.hygieneauditor.rest.FindingBean;
import io.jenkins.plugins.hygieneauditor.rest.FindingService;
import io.jenkins.plugins.hygieneauditor.rest.FindingsBean;
import io.jenkins.plugins.hygieneauditor.rest.SummaryBean;
import io.jenkins.plugins.hygieneauditor.scan.ScanAlreadyRunningException;
import io.jenkins.plugins.hygieneauditor.scan.ScanRequest;
import io.jenkins.plugins.hygieneauditor.scan.ScanService;
import io.jenkins.plugins.hygieneauditor.store.FindingStoreHolder;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jenkins.model.Jenkins;
import jenkins.security.stapler.StaplerAccessibleType;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/** Entry point under Manage Jenkins. Placeholder page in the skeleton; real dashboard arrives with the UI step. */
@Extension
public class HygieneAuditorLink extends ManagementLink {

    @CheckForNull
    @Override
    public String getIconFileName() {
        return "symbol-shield-warning";
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return Messages.HygieneAuditorLink_DisplayName();
    }

    @Override
    public String getDescription() {
        return Messages.HygieneAuditorLink_Description();
    }

    @CheckForNull
    @Override
    public String getUrlName() {
        return "hygiene-auditor";
    }

    @NonNull
    @Override
    public Category getCategory() {
        return Category.STATUS;
    }

    @NonNull
    @Override
    public Permission getRequiredPermission() {
        return HygieneAuditorPermissions.VIEW;
    }

    // -------------------------------------------------------------- UI model

    /** One collector's result in the last scan, for the status header. */
    public record ScanChip(String name, String status, String statusLabel, long items, int findings, String message) {}

    /** The last scan as the header shows it. {@code chips} is empty when only saved history is available. */
    public record ScanView(
            String endedAgo, String endedIso, String state, String stateLabel, String trigger, List<ScanChip> chips) {}

    /** The last scan: from memory if this controller ran one since it started, otherwise from the saved history. */
    public ScanView getLastScanView() throws IOException {
        io.jenkins.plugins.hygieneauditor.scan.ScanRecord r = ScanService.get().lastScan();
        if (r != null) {
            List<ScanChip> chips = new java.util.ArrayList<>();
            for (var o : r.outcomes()) {
                String name = io.jenkins.plugins.hygieneauditor.collector.HygieneCollector.all().stream()
                        .filter(c -> c.getId().equals(o.collectorId()))
                        .map(io.jenkins.plugins.hygieneauditor.collector.HygieneCollector::getDisplayName)
                        .findFirst()
                        .orElse(o.collectorId());
                chips.add(new ScanChip(
                        name,
                        o.status().name(),
                        io.jenkins.plugins.hygieneauditor.ui.Labels.collectorStatus(
                                o.status().name()),
                        o.itemsExamined(),
                        o.findings(),
                        o.message()));
            }
            return new ScanView(
                    io.jenkins.plugins.hygieneauditor.ui.Labels.ago(r.end()),
                    r.end().toString(),
                    r.state().name(),
                    io.jenkins.plugins.hygieneauditor.ui.Labels.scanState(
                            r.state().name()),
                    r.trigger(),
                    chips);
        }
        var history = FindingStoreHolder.get().history().all();
        if (history.isEmpty()) {
            return null;
        }
        var h = history.get(history.size() - 1);
        List<ScanChip> chips = new java.util.ArrayList<>();
        for (var c : h.collectors()) {
            chips.add(new ScanChip(
                    collectorName(c.id()),
                    c.status(),
                    io.jenkins.plugins.hygieneauditor.ui.Labels.collectorStatus(c.status()),
                    c.items(),
                    c.findings(),
                    null));
        }
        return new ScanView(
                io.jenkins.plugins.hygieneauditor.ui.Labels.ago(h.end()),
                h.end().toString(),
                h.state(),
                io.jenkins.plugins.hygieneauditor.ui.Labels.scanState(h.state()),
                h.trigger(),
                chips);
    }

    private static String collectorName(String id) {
        return io.jenkins.plugins.hygieneauditor.collector.HygieneCollector.all().stream()
                .filter(c -> c.getId().equals(id))
                .map(io.jenkins.plugins.hygieneauditor.collector.HygieneCollector::getDisplayName)
                .findFirst()
                .orElse(id);
    }

    // Headline numbers take the already-built summary: building it re-filters every finding for the viewer.
    public int openTotal(SummaryBean s) {
        return s.getOpenByCategory().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }

    public int highSeverityCount(SummaryBean s) {
        return s.getOpenBySeverity().getOrDefault("HIGH", 0);
    }

    public int highConfidenceCount(SummaryBean s) {
        return s.getOpenByConfidence().getOrDefault("HIGH", 0);
    }

    public int decidedCount(SummaryBean s) {
        var st = s.getByStatus();
        return st.getOrDefault("ACKNOWLEDGED", 0) + st.getOrDefault("IGNORED", 0);
    }

    /** Sum of a card's values, at least 1 (the maximum of its progress bars). */
    public int sumOf(java.util.Map<String, Integer> m) {
        return Math.max(1, m.values().stream().mapToInt(Integer::intValue).sum());
    }

    /** Entries most important first (High before Low), for the severity and confidence cards. */
    public List<java.util.Map.Entry<String, Integer>> mostImportantFirst(java.util.Map<String, Integer> m) {
        List<java.util.Map.Entry<String, Integer>> l = new java.util.ArrayList<>(m.entrySet());
        java.util.Collections.reverse(l);
        return l;
    }

    public SummaryBean getSummary() throws IOException {
        return new SummaryBean();
    }

    public io.jenkins.plugins.hygieneauditor.ui.FindingsPanel getPanel() throws IOException {
        StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        io.jenkins.plugins.hygieneauditor.rest.FindingQuery q =
                io.jenkins.plugins.hygieneauditor.rest.FindingQuery.fromRequest(req);
        return new io.jenkins.plugins.hygieneauditor.ui.FindingsPanel(
                q,
                FindingService.query(q),
                true,
                FindingService.categoryCounts(q),
                req.getParameter("selected"),
                "setStatus");
    }

    /** One category card of the headline row. {@code tone} is red, amber, grey or none. */
    public record Card(String label, int count, String pill, String tone, String url) {}

    /** The ring: review progress, with its plain-language reading. */
    public record Ring(int percent, String filled, String rest, String headline, String detail, String delta) {}

    /** Cards for the headline row, one per category, each with the one fact most worth knowing. */
    public List<Card> cardsFor(SummaryBean s) {
        List<Card> cards = new java.util.ArrayList<>();
        for (var c : io.jenkins.plugins.hygieneauditor.model.Category.values()) {
            int n = s.getOpenByCategory().getOrDefault(c.name(), 0);
            String pill = "";
            String tone = "none";
            switch (c) {
                case CREDENTIALS -> {
                    int hi = s.openHighConfidence(c);
                    pill = hi > 0 ? Messages.Card_HighConfidence(hi) : n > 0 ? "" : Messages.Card_AllClear();
                    tone = hi > 0 ? "red" : n > 0 ? "none" : "grey";
                }
                case JOBS -> {
                    int nb = s.openOfType("job.never_built");
                    pill = nb > 0 ? Messages.Card_NeverBuilt(nb) : n > 0 ? "" : Messages.Card_AllClear();
                    tone = nb > 0 ? "amber" : n > 0 ? "none" : "grey";
                }
                case AGENTS -> {
                    pill = n > 0
                            ? Messages.Card_OfflineOver(
                                    HygieneAuditorConfiguration.get().getOfflineAgentDays())
                            : Messages.Card_AllClear();
                    tone = n > 0 ? "amber" : "grey";
                }
                case SHARED_LIBRARIES -> {
                    int uv = s.openOfType("library.unused_variable");
                    pill = uv > 0 ? Messages.Card_UnusedVariables(uv) : n > 0 ? "" : Messages.Card_AllClear();
                    tone = "grey";
                }
                case PLUGINS -> {
                    int od = s.openOfType("plugin.orphaned_data");
                    int nr = s.openOfType("plugin.needs_review");
                    pill = od > 0
                            ? Messages.Card_OrphanedData(od)
                            : nr > 0 ? Messages.Card_NeedReview(nr) : Messages.Card_AllClear();
                    tone = od > 0 ? "amber" : "grey";
                }
            }
            cards.add(new Card(
                    io.jenkins.plugins.hygieneauditor.ui.Labels.category(c), n, pill, tone, "?category=" + c.name()));
        }
        return cards;
    }

    /** Review progress and how the open count moved since the previous scan. */
    public Ring ringFor(SummaryBean s) throws IOException {
        int reviewed = s.reviewedCount();
        int total = reviewed + s.openCount();
        int percent = total == 0 ? 100 : (int) Math.round(100.0 * reviewed / total);
        double circumference = 2 * Math.PI * 42;
        double filled = circumference * percent / 100.0;
        String headline = total == 0
                ? Messages.Ring_Headline_Nothing()
                : percent == 100
                        ? Messages.Ring_Headline_Done()
                        : percent >= 50 ? Messages.Ring_Headline_OnTrack() : Messages.Ring_Headline_Needs();
        String delta = "";
        var history = FindingStoreHolder.get().history().all();
        if (history.size() >= 2) {
            int diff = history.get(history.size() - 1).totalOpen()
                    - history.get(history.size() - 2).totalOpen();
            delta = diff > 0
                    ? Messages.Ring_Delta_More(diff)
                    : diff < 0 ? Messages.Ring_Delta_Fewer(-diff) : Messages.Ring_Delta_Same();
        }
        return new Ring(
                percent,
                String.format(java.util.Locale.ROOT, "%.1f", filled),
                String.format(java.util.Locale.ROOT, "%.1f", circumference - filled),
                headline,
                total == 0 ? "" : Messages.Ring_Detail(reviewed, total),
                delta);
    }

    /** Items examined in the last scan, summed over collectors (0 when only the saved history is available). */
    public long itemsExamined(ScanView v) {
        return v == null ? 0 : v.chips().stream().mapToLong(ScanChip::items).sum();
    }

    public String categoryLabel(String name) {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.category(name);
    }

    public String severityLabel(String name) {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.severity(name);
    }

    public String confidenceLabel(String name) {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.confidence(name);
    }

    public String statusLabel(String name) {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.status(name);
    }

    public boolean isCanScan() {
        return Jenkins.get().hasPermission(HygieneAuditorPermissions.SCAN);
    }

    public boolean isCanConfigure() {
        return Jenkins.get().hasPermission(Jenkins.MANAGE);
    }

    /** The trend is built from instance-wide totals, so only administrators see it. */
    public boolean isTrendVisible() {
        return Jenkins.get().hasPermission(Jenkins.ADMINISTER);
    }

    public String getTrendSvg() throws IOException {
        return isTrendVisible()
                ? io.jenkins.plugins.hygieneauditor.ui.TrendChart.render(
                        FindingStoreHolder.get().history().all(), true)
                : "";
    }

    public java.util.Optional<io.jenkins.plugins.hygieneauditor.scan.ScanStatus> getRunningScan() {
        return ScanService.get().status();
    }

    // ------------------------------------------------------------------ REST

    /** {@code /manage/hygiene-auditor/api/json}: counts and scan state, limited to what the caller may see. */
    public Api getApi() throws IOException {
        Jenkins.get().checkPermission(HygieneAuditorPermissions.VIEW);
        return new Api(new SummaryBean());
    }

    /** {@code /manage/hygiene-auditor/findings/api/json?category=&status=&minConfidence=...} */
    public FindingsView getFindings() {
        Jenkins.get().checkPermission(HygieneAuditorPermissions.VIEW);
        return new FindingsView();
    }

    /** {@code /manage/hygiene-auditor/finding/<id>/api/json} */
    public FindingBean getFinding(String id) throws IOException {
        Jenkins.get().checkPermission(HygieneAuditorPermissions.VIEW);
        return FindingService.visible(id)
                .map(f -> new FindingBean(f, "../../setStatus"))
                .orElse(null);
    }

    /** Wrapper so Stapler can route {@code findings/api/json}. */
    @StaplerAccessibleType // Jenkins blocks getter-based routing unless the returned type opts in
    public static final class FindingsView {
        public Api getApi() throws IOException {
            return FindingsBean.api();
        }
    }

    // --------------------------------------------------------------- downloads

    /** All findings the caller may see that match the filter (no paging), worst first. */
    private java.util.List<Finding> matching(io.jenkins.plugins.hygieneauditor.rest.FindingQuery q) throws IOException {
        return FindingService.visible().stream()
                .filter(q::matches)
                .sorted(java.util.Comparator.comparing(Finding::severity)
                        .reversed()
                        .thenComparing(java.util.Comparator.comparing(Finding::confidence)
                                .reversed())
                        .thenComparing(Finding::id))
                .toList();
    }

    private static void download(StaplerResponse2 rsp, String contentType, String filename) {
        rsp.setContentType(contentType + ";charset=UTF-8");
        rsp.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        rsp.setHeader("X-Content-Type-Options", "nosniff");
        rsp.setHeader("Cache-Control", "no-store");
    }

    /** {@code exportJson?category=&status=&...}: every matching finding, in the on-disk schema. */
    public void doExportJson(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(HygieneAuditorPermissions.VIEW);
        var q = io.jenkins.plugins.hygieneauditor.rest.FindingQuery.fromRequest(req);
        download(rsp, "application/json", "hygiene-findings.json");
        io.jenkins.plugins.hygieneauditor.export.JsonExporter.write(matching(q), Instant.now(), rsp.getWriter());
    }

    /** {@code exportCsv?...}: same selection as CSV, safe to open in a spreadsheet. */
    public void doExportCsv(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(HygieneAuditorPermissions.VIEW);
        var q = io.jenkins.plugins.hygieneauditor.rest.FindingQuery.fromRequest(req);
        download(rsp, "text/csv", "hygiene-findings.csv");
        io.jenkins.plugins.hygieneauditor.export.CsvExporter.write(matching(q), rsp.getWriter());
    }

    /**
     * {@code removalPlan?format=groovy|cli|checklist&minConfidence=HIGH&...}: a script or checklist for an
     * administrator to review and run <em>by hand</em>. Nothing here changes anything. Only OPEN findings are
     * included, and the confidence floor defaults to HIGH.
     */
    public void doRemovalPlan(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(HygieneAuditorPermissions.VIEW);
        io.jenkins.plugins.hygieneauditor.export.RemovalPlan.Format format;
        try {
            format = io.jenkins.plugins.hygieneauditor.export.RemovalPlan.Format.parse(req.getParameter("format"));
        } catch (IllegalArgumentException e) {
            rsp.sendError(400, "format must be groovy, cli or checklist");
            return;
        }
        var q = io.jenkins.plugins.hygieneauditor.rest.FindingQuery.fromRequest(req);
        String mc = req.getParameter("minConfidence");
        if (mc == null || mc.isBlank()) {
            q = q.withMinConfidence(io.jenkins.plugins.hygieneauditor.model.Confidence.HIGH);
        }
        if (req.getParameter("status") == null) {
            q = new io.jenkins.plugins.hygieneauditor.rest.FindingQuery(
                    q.categories(),
                    EnumSet.of(Status.OPEN),
                    q.minConfidence(),
                    q.minSeverity(),
                    q.folder(),
                    q.limit(),
                    0);
        }
        java.util.List<Finding> selected = matching(q);
        String onlyId = req.getParameter("id");
        if (onlyId != null
                && !onlyId.isBlank()) { // the side panel's button: exactly this finding, if the user may see it
            selected = FindingService.visible(onlyId.trim())
                    .map(java.util.List::of)
                    .orElse(java.util.List.of());
        }
        String user = Jenkins.getAuthentication2().getName();
        java.util.logging.Logger.getLogger(HygieneAuditorLink.class.getName())
                .log(java.util.logging.Level.INFO, "{0} generated a {1} removal plan with {2} item(s)", new Object[] {
                    user, format, selected.size()
                });
        if ("true".equals(req.getParameter("view"))) { // show it in the browser instead of saving a file
            rsp.setContentType("text/plain;charset=UTF-8");
            rsp.setHeader("X-Content-Type-Options", "nosniff");
            rsp.setHeader("Cache-Control", "no-store");
        } else {
            download(rsp, format.contentType(), "hygiene-removal-plan." + format.extension());
        }
        rsp.getWriter()
                .write(io.jenkins.plugins.hygieneauditor.export.RemovalPlan.generate(
                        selected, format, user, Instant.now()));
    }

    // --------------------------------------------------------------- actions

    @RequirePOST
    public HttpResponse doScan(@QueryParameter String categories, @QueryParameter boolean fromUi) throws IOException {
        Jenkins.get().checkPermission(HygieneAuditorPermissions.SCAN);
        Set<io.jenkins.plugins.hygieneauditor.model.Category> cats =
                EnumSet.noneOf(io.jenkins.plugins.hygieneauditor.model.Category.class);
        try {
            if (categories != null) {
                for (String c : categories.split(",")) {
                    if (!c.isBlank()) {
                        cats.add(io.jenkins.plugins.hygieneauditor.model.Category.valueOf(
                                c.trim().toUpperCase(Locale.ROOT)));
                    }
                }
            }
            ScanService.Handle h = ScanService.get().start(new ScanRequest(cats, "manual"));
            JsonObject o = new JsonObject();
            o.addProperty("scanId", h.scanId());
            return fromUi ? HttpResponses.redirectToDot() : json(202, o);
        } catch (ScanAlreadyRunningException e) {
            return fromUi ? HttpResponses.redirectToDot() : error(409, "A scan is already running");
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    @RequirePOST
    public HttpResponse doCancel(@QueryParameter boolean fromUi) {
        Jenkins.get().checkPermission(HygieneAuditorPermissions.SCAN);
        JsonObject o = new JsonObject();
        o.addProperty("cancelled", ScanService.get().cancel());
        return fromUi ? HttpResponses.redirectToDot() : json(200, o);
    }

    /** Acknowledge, ignore (reason required, optional snooze in days) or reopen a finding. */
    @RequirePOST
    public HttpResponse doSetStatus(
            @QueryParameter String id,
            @QueryParameter String status,
            @QueryParameter String reason,
            @QueryParameter String snoozeDays,
            @QueryParameter boolean fromUi)
            throws IOException {
        Jenkins.get().checkPermission(HygieneAuditorPermissions.SCAN);
        Finding f = FindingService.visible(id == null ? "" : id).orElse(null);
        if (f == null) {
            return error(404, "No such finding");
        }
        try {
            Status st = Status.valueOf(status == null ? "" : status.trim().toUpperCase(Locale.ROOT));
            Instant now = Instant.now();
            Instant snooze = snoozeDays == null || snoozeDays.isBlank()
                    ? null
                    : now.plus(Long.parseLong(snoozeDays.trim()), ChronoUnit.DAYS);
            Finding updated = FindingStoreHolder.get()
                    .store()
                    .setStatus(
                            f.id(),
                            st,
                            reason,
                            snooze,
                            Jenkins.getAuthentication2().getName(),
                            now)
                    .orElse(null);
            if (updated == null) {
                return error(404, "No such finding");
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", updated.id());
            o.addProperty("status", updated.status().name());
            return fromUi ? HttpResponses.redirectTo("finding/" + updated.id() + "/") : json(200, o);
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    private static HttpResponse error(int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return json(code, o);
    }

    private static HttpResponse json(int code, JsonObject body) {
        return new HttpResponse() {
            @Override
            public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node) throws IOException {
                rsp.setStatus(code);
                rsp.setContentType("application/json;charset=UTF-8");
                rsp.getWriter().write(body.toString());
            }
        };
    }
}
