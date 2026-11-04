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
        assertTrue(text.contains("By category") && text.contains("By severity") && text.contains("By confidence"));
        assertTrue(text.contains("No scan has run yet"));
        HtmlElement caption = page.getFirstByXPath("//table[contains(@class,'ha-table')]/caption");
        assertNotNull(caption, "table has a caption");
        assertTrue(caption.asNormalizedText().contains("1–2 / 2"), caption.asNormalizedText());
        assertEquals(
                6,
                page.getByXPath("//table[contains(@class,'ha-table')]/thead/tr/th[@scope='col']")
                        .size());
        assertNotNull(page.getFormByName("scan"), "admin can start a scan");
        assertTrue(text.contains("Medium") && text.contains("High"), "badges carry text, not only colour");
        assertTrue(text.contains("The trend appears after at least two scans"));
    }

    @Test
    void filtersNarrowTheTableAndPagingLinksCarryThem() throws Exception {
        JenkinsRule.WebClient wc = as("admin");
        HtmlPage high = wc.goTo(BASE + "?minConfidence=HIGH");
        assertTrue(high.asNormalizedText().contains("1–1 / 1"));
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
        assertTrue(page.asNormalizedText().contains("1–2 / 2"));
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
    void pageUsesThemeVariablesAndNoInlineScripts() throws Exception {
        HtmlPage page = as("admin").goTo(BASE);
        assertTrue(page.getByXPath("//main//script[not(@src)]").isEmpty(), "no inline script in our content");
        String css = new String(getClass()
                .getResourceAsStream("/io/jenkins/plugins/hygieneauditor/style.css")
                .readAllBytes());
        assertTrue(css.contains("var(--text-color") && css.contains("var(--card-border-color"));
        assertFalse(
                css.matches("(?s).*#[0-9a-fA-F]{6}[^)]*;.*") && !css.contains("var("),
                "colours come from theme variables");
    }
}
