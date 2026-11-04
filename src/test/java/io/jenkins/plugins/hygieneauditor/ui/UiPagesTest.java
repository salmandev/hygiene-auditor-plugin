package io.jenkins.plugins.hygieneauditor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorPermissions;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Status;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import io.jenkins.plugins.hygieneauditor.store.FindingStoreHolder;
import io.jenkins.plugins.hygieneauditor.store.ScanHistoryEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class UiPagesTest {
    static final String BASE = "manage/hygiene-auditor/";
    JenkinsRule j;
    Finding stale;
    Finding evil;

    private static Finding finding(
            String type, String kind, String name, String display, Severity s, Confidence c, String evidence) {
        return Finding.observed(
                new FindingCandidate(
                        type,
                        Category.JOBS,
                        Subject.of(kind, name, display),
                        s,
                        c,
                        List.of(
                                new Evidence("job-metadata", evidence, 50),
                                new Evidence("job-metadata", "second line", 0)),
                        "Review it"),
                "jobs",
                Instant.now());
    }

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.createFreeStyleProject("old-build");
        j.createFreeStyleProject("x");
        stale = finding(
                "job.stale",
                "job",
                "old-build",
                "old-build",
                Severity.LOW,
                Confidence.HIGH,
                "Last build started 400 days ago");
        evil = finding(
                "job.stale",
                "job",
                "x",
                "<script>window.pwned=1</script>",
                Severity.MEDIUM,
                Confidence.MEDIUM,
                "<img src=x onerror=window.pwned=1>");
        FindingStoreHolder.get().store().recordScan(List.of(stale, evil), Set.of(), Instant.now());

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ, Item.READ, HygieneAuditorPermissions.VIEW)
                .everywhere()
                .to("viewer"));
    }

    private JenkinsRule.WebClient as(String user) {
        return j.createWebClient().withBasicCredentials(user);
    }

    @Test
    void dashboardShowsCountsFindingsAndControlsForAnAdmin() throws Exception {
        HtmlPage page = as("admin").goTo(BASE);
        String text = page.asNormalizedText();
        assertTrue(text.contains("Hygiene Auditor"));
        assertTrue(text.contains("Controller hygiene"));
        assertTrue(text.contains("Review progress"), "the ring");
        assertEquals(5, page.getByXPath("//a[contains(@class,'ha-metric')]").size(), "one card per category");
        assertTrue(text.contains("Jobs and folders") && text.contains("Credentials") && text.contains("Agents"));
        assertTrue(text.contains("No scan has run yet"));
        HtmlElement caption = page.getFirstByXPath("//table[contains(@class,'ha-table')]/caption");
        assertNotNull(caption, "table has a caption");
        assertTrue(caption.asNormalizedText().contains("1–2 of 2"), caption.asNormalizedText());
        assertEquals(
                4,
                page.getByXPath("//table[contains(@class,'ha-table')]/thead/tr/th[@scope='col']")
                        .size(),
                "confidence, finding (with type and severity beneath), evidence, last seen");
        assertNotNull(page.getFormByName("scan"), "admin can start a scan");
        assertTrue(text.contains("Medium") && text.contains("High"), "badges carry text, not only colour");
        assertTrue(text.contains("The trend appears after at least two scans"));
    }

    @Test
    void filtersNarrowTheTableAndPagingLinksCarryThem() throws Exception {
        JenkinsRule.WebClient wc = as("admin");
        HtmlPage high = wc.goTo(BASE + "?minConfidence=HIGH");
        assertTrue(high.asNormalizedText().contains("1–1 of 1"));
        HtmlPage none = wc.goTo(BASE + "?category=AGENTS");
        assertTrue(none.asNormalizedText().contains("No findings match the current filter"));
        HtmlPage paged = wc.goTo(BASE + "?limit=1");
        assertTrue(paged.asXml().contains("offset=1"), "next link");
        assertFalse(wc.goTo(BASE + "?limit=1&offset=1").asXml().contains("offset=2"));
    }

    @Test
    void userInputIsEscapedEverywhere() throws Exception {
        JenkinsRule.WebClient wc = as("admin");
        HtmlPage list = wc.goTo(BASE);
        assertTrue(list.asNormalizedText().contains("<script>window.pwned=1</script>"), "shown literally");
        assertTrue(list.getByXPath("//script[contains(.,'pwned')]").isEmpty(), "never executed");
        HtmlPage detail = wc.goTo(BASE + "finding/" + evil.id() + "/");
        assertTrue(detail.asNormalizedText().contains("<img src=x onerror=window.pwned=1>"));
        assertTrue(detail.getByXPath("//img[@src='x']").isEmpty());
    }

    @Test
    void detailPageExplainsTheEvidenceAndLetsAnAdminDecide() throws Exception {
        JenkinsRule.WebClient wc = as("admin");
        HtmlPage detail = wc.goTo(BASE + "finding/" + stale.id() + "/");
        String text = detail.asNormalizedText();
        assertTrue(text.contains("Last build started 400 days ago") && text.contains("second line"));
        assertTrue(text.contains("Review it") && text.contains("old-build"));
        assertFalse(detail.getByXPath("//table/caption").isEmpty());

        HtmlForm form = detail.getFormByName("status");
        form.getSelectByName("status").setSelectedAttribute("IGNORED", true);
        form.getInputByName("reason").setValue("planned decommission");
        form.getInputByName("snoozeDays").setValue("14");
        HtmlPage after = j.submit(form);
        assertTrue(after.getUrl().toString().contains("/finding/" + stale.id()), "redirected back to the finding");
        Finding f = FindingStoreHolder.get().store().get(stale.id()).orElseThrow();
        assertEquals(Status.IGNORED, f.status());
        assertEquals("planned decommission", f.statusReason());
        assertTrue(after.asNormalizedText().contains("planned decommission"));
    }

    @Test
    void viewerSeesFindingsButNoScanOrDecisionControls() throws Exception {
        JenkinsRule.WebClient wc = as("viewer");
        HtmlPage page = wc.goTo(BASE);
        assertTrue(page.asNormalizedText().contains("1–2 of 2"));
        assertTrue(page.getForms().stream()
                .noneMatch(f -> "scan".equals(f.getNameAttribute()) || "cancel".equals(f.getNameAttribute())));
        HtmlPage detail = wc.goTo(BASE + "finding/" + stale.id() + "/");
        assertTrue(
                detail.getForms().stream().noneMatch(f -> "status".equals(f.getNameAttribute())), "no decision form");
    }

    @Test
    void trendIsDrawnForAdminsOnlyOnceThereAreTwoScans() throws Exception {
        var history = FindingStoreHolder.get().history();
        history.append(entry(1, 3, 0));
        history.append(entry(2, 8, 3));
        HtmlPage admin = as("admin").goTo(BASE);
        assertFalse(
                admin.getByXPath("//*[local-name()='svg' and contains(@class,'ha-trend')]")
                        .isEmpty(),
                "svg present");
        assertTrue(admin.asXml().contains("2 scans, latest 8 open, 3 of them high severity"));
        HtmlPage viewer = as("viewer").goTo(BASE);
        assertTrue(viewer.getByXPath("//*[local-name()='svg' and contains(@class,'ha-trend')]")
                .isEmpty());
        assertTrue(viewer.asNormalizedText().contains("shown to administrators only"));
    }

    private static ScanHistoryEntry entry(int n, int total, int high) {
        return new ScanHistoryEntry(
                "s" + n,
                "test",
                Instant.now().minusSeconds(86400L * (3 - n)),
                "COMPLETED",
                Map.of("JOBS", total),
                Map.of("HIGH", high, "LOW", total - high));
    }

    @Test
    void theHeaderShowsCollectorChipsFromSavedHistoryAfterARestart() throws Exception {
        FindingStoreHolder.get()
                .history()
                .append(new ScanHistoryEntry(
                        "s1",
                        "scheduled",
                        Instant.now().minusSeconds(300),
                        "COMPLETED",
                        Map.of("JOBS", 2),
                        Map.of("LOW", 2),
                        List.of(
                                new io.jenkins.plugins.hygieneauditor.store.CollectorSummary(
                                        "jobs", "COMPLETE", 120, 2),
                                new io.jenkins.plugins.hygieneauditor.store.CollectorSummary(
                                        "agents", "PARTIAL", 3, 0))));
        HtmlPage page = as("admin").goTo(BASE);
        String text = page.asNormalizedText();
        assertTrue(text.contains("Last scan"), text);
        assertTrue(
                text.contains("5 min ago") || text.contains("4 min") || text.contains("5 min"),
                "relative time: " + text);
        assertTrue(text.contains("ago"), "reads as 'ago', not a bare duration");
        assertFalse(
                page.getByXPath("//li[contains(@class,'ha-chip-partial')]").isEmpty(),
                "a partial collector is flagged");
        assertEquals(2, page.getByXPath("//li[contains(@class,'ha-chip')]").size());
        assertTrue(text.contains("Jobs and folders") && text.contains("Agents"), "localised collector names, not ids");
    }

    @Test
    void timestampsAreHumanNotRawNanosecondIso() throws Exception {
        HtmlPage detail = as("admin").goTo(BASE + "finding/" + stale.id() + "/");
        String text = detail.asNormalizedText();
        assertTrue(text.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} UTC.*"), text);
        assertFalse(
                text.matches("(?s).*T\\d{2}:\\d{2}:\\d{2}\\.\\d{3,}Z.*"), "no raw fractional ISO in the visible text");
        String list = as("admin").goTo(BASE).asNormalizedText();
        assertFalse(list.matches("(?s).*T\\d{2}:\\d{2}:\\d{2}\\.\\d{3,}Z.*"), "nor on the dashboard");
    }

    @Test
    void typesAndStatusesUsePlainLanguage() throws Exception {
        String text = as("admin").goTo(BASE).asNormalizedText();
        assertTrue(text.contains("Stale job") && !text.contains("job.stale"), text);
    }

    @Test
    void theSidePanelShowsWhyForTheFirstRowAndFollowsASelection() throws Exception {
        JenkinsRule.WebClient wc = as("admin");
        // Worst first: the MEDIUM-severity finding (evil) leads, so it is the default selection.
        HtmlPage first = wc.goTo(BASE);
        String firstPanel = panel(first);
        assertTrue(firstPanel.contains("Why we think it is unused"), firstPanel);
        assertTrue(
                firstPanel.contains("<img src=x onerror=window.pwned=1>"),
                "its evidence, shown as text: " + firstPanel);
        assertTrue(firstPanel.contains("What was not checked"), "caveats are shown, not hidden: " + firstPanel);
        assertTrue(
                firstPanel.contains("Confirm with the owner first"),
                "plain-language reading of the confidence: " + firstPanel);
        assertEquals(
                1, first.getByXPath("//tr[contains(@class,'ha-row-selected')]").size(), "exactly one highlighted row");
        assertEquals(
                1,
                first.getByXPath("//a[contains(@href,'removalPlan') and contains(@href,'id=" + evil.id() + "')]")
                        .size());

        HtmlPage other = wc.goTo(BASE + "?selected=" + stale.id());
        String otherPanel = panel(other);
        assertTrue(
                otherPanel.contains("Last build started 400 days ago"),
                "the selection drives the panel: " + otherPanel);
        assertFalse(otherPanel.contains("onerror"), otherPanel);
        assertEquals(
                1,
                other.getByXPath("//a[contains(@href,'removalPlan') and contains(@href,'id=" + stale.id() + "')]")
                        .size(),
                "plan for exactly this finding");
        assertEquals(
                0,
                other.getByXPath("//a[contains(@href,'removalPlan') and contains(@href,'id=" + evil.id() + "')]")
                        .size());
        assertEquals(
                1, other.getByXPath("//tr[contains(@class,'ha-row-selected')]").size());
    }

    private static String panel(HtmlPage page) {
        return ((HtmlElement) page.getFirstByXPath("//section[@aria-label='Selected finding']")).asNormalizedText();
    }

    @Test
    void categoryPillsCountFindingsAndKeepTheOtherFilters() throws Exception {
        HtmlPage page = as("admin").goTo(BASE + "?minConfidence=HIGH");
        var pills = page.<HtmlElement>getByXPath("//nav[@aria-label='Filter by category']/a");
        assertEquals("All 1", pills.get(0).asNormalizedText(), "counted under the other filters");
        assertTrue(pills.get(0).getAttribute("class").contains("ha-fpill-active"));
        assertEquals("Jobs and folders 1", pills.get(1).asNormalizedText());
        assertTrue(pills.get(1).getAttribute("href").contains("minConfidence=HIGH"), "the pill keeps the filter");
        assertTrue(pills.get(1).getAttribute("href").contains("category=JOBS"));
        HtmlPage jobs = as("admin").goTo(BASE + "?category=JOBS");
        assertTrue(jobs.<HtmlElement>getByXPath(
                        "//nav[@aria-label='Filter by category']/a[contains(@class,'ha-fpill-active')]")
                .get(0)
                .asNormalizedText()
                .startsWith("Jobs and folders"));
    }

    @Test
    void theReviewRingCountsDecisionsAndSaysSo() throws Exception {
        FindingStoreHolder.get()
                .store()
                .setStatus(stale.id(), Status.ACKNOWLEDGED, "fine", null, "admin", Instant.now());
        String text = as("admin").goTo(BASE).asNormalizedText();
        assertTrue(text.contains("Review progress"), text);
        assertTrue(text.contains("1 of 2 findings have a decision"), text);
        assertTrue(text.contains("On track"), "50% reads as on track: " + text);
    }

    @Test
    void pageUsesThemeVariablesAndNoInlineScripts() throws Exception {
        HtmlPage page = as("admin").goTo(BASE);
        assertTrue(page.getByXPath("//main//script[not(@src)]").isEmpty(), "no inline script in our content");
        String css = new String(getClass()
                .getResourceAsStream("/io/jenkins/plugins/hygieneauditor/style.css")
                .readAllBytes());
        // Light palette by default; the dark palette follows Jenkins' own theme switch, in both of its forms.
        assertTrue(css.contains("html[data-theme=\"dark\"] .ha-page"), "fixed dark theme");
        assertTrue(
                css.contains("prefers-color-scheme: dark") && css.contains("html[data-theme=\"dark-system\"] .ha-page"),
                "system-following dark theme");
        assertTrue(
                css.contains("var(--ha-card)") && css.contains("var(--ha-text)"),
                "components use the palette variables");
        // Nothing outside the palette definitions may hard-code a text or card colour.
        String componentRules = css.substring(css.indexOf("/* ---- header */"));
        assertFalse(
                componentRules.matches("(?s).*\\b(?:color|background):\\s*#[0-9a-fA-F]{3,8}\\b.*"),
                "colours come from the palette variables");
    }
}
