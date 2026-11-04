package io.jenkins.plugins.hygieneauditor.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.slaves.DumbSlave;
import hudson.slaves.OfflineCause;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorPermissions;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import io.jenkins.plugins.hygieneauditor.store.FindingStoreHolder;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ExportEndpointsTest {
    static final String BASE = "manage/hygiene-auditor/";
    static final String HOSTILE = "'; System.setProperty('hygiene.pwned','x'); '";
    JenkinsRule j;

    private static Finding finding(String type, Category cat, String kind, String name, Confidence c) {
        return Finding.observed(
                new FindingCandidate(
                        type,
                        cat,
                        Subject.of(kind, name, null),
                        Severity.MEDIUM,
                        c,
                        List.of(new Evidence("t", "evidence for " + name, 50)),
                        "act"),
                "col",
                Instant.now());
    }

    private void seed(Finding... fs) throws Exception {
        FindingStoreHolder.get().store().recordScan(List.of(fs), Set.of(), Instant.now());
    }

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
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
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return user == null ? wc : wc.withBasicCredentials(user);
    }

    // ----------------------------------------------------------- endpoints

    @Test
    void csvAndJsonDownloadsHaveSafeHeadersAndHonourFilters() throws Exception {
        j.createFreeStyleProject("p1");
        j.createFreeStyleProject("p2");
        seed(
                finding("job.stale", Category.JOBS, "job", "p1", Confidence.HIGH),
                finding("job.stale", Category.JOBS, "job", "p2", Confidence.LOW),
                finding("agent.offline", Category.AGENTS, "agent", "n1", Confidence.MEDIUM));

        var csv = as("admin").goTo(BASE + "exportCsv", null).getWebResponse();
        assertEquals(200, csv.getStatusCode());
        assertTrue(csv.getResponseHeaderValue("Content-Disposition").contains("attachment"));
        assertEquals("nosniff", csv.getResponseHeaderValue("X-Content-Type-Options"));
        assertTrue(csv.getContentType().startsWith("text/csv"));
        assertEquals(4, csv.getContentAsString().strip().split("\r\n").length, "header + 3 findings");

        var filtered = as("admin")
                .goTo(BASE + "exportCsv?category=JOBS&minConfidence=HIGH", null)
                .getWebResponse();
        assertEquals(2, filtered.getContentAsString().strip().split("\r\n").length);

        var json = as("admin").goTo(BASE + "exportJson?status=all", null).getWebResponse();
        JsonObject root = JsonParser.parseString(json.getContentAsString()).getAsJsonObject();
        assertEquals(3, root.get("count").getAsInt());
        assertTrue(json.getResponseHeaderValue("Content-Disposition").contains("hygiene-findings.json"));
    }

    @Test
    void downloadsAreFilteredByWhatTheUserMaySee() throws Exception {
        j.createFreeStyleProject("p1");
        seed(
                finding("job.stale", Category.JOBS, "job", "p1", Confidence.HIGH),
                finding("agent.offline", Category.AGENTS, "agent", "secret-node", Confidence.HIGH),
                finding("credential.unused", Category.CREDENTIALS, "credential", ":_:secret-cred", Confidence.HIGH));
        for (String path : List.of("exportCsv", "exportJson", "removalPlan?format=checklist")) {
            String body = as("viewer").goTo(BASE + path, null).getWebResponse().getContentAsString();
            assertTrue(body.contains("p1"), path);
            assertFalse(body.contains("secret-node") || body.contains("secret-cred"), path + " leaked");
            String admin = as("admin").goTo(BASE + path, null).getWebResponse().getContentAsString();
            assertTrue(admin.contains("secret-node") && admin.contains("secret-cred"), path + " admin sees all");
        }
    }

    @Test
    void anonymousGetsNoData() throws Exception {
        seed(finding("job.stale", Category.JOBS, "job", "p1", Confidence.HIGH));
        for (String path : List.of("exportCsv", "exportJson", "removalPlan")) {
            var r = as(null).goTo(BASE + path, null).getWebResponse();
            assertFalse(r.getContentAsString().contains("p1"), path);
            assertFalse(r.getContentType().contains("csv") || r.getContentType().contains("json"), path);
        }
    }

    @Test
    void removalPlanDefaultsToHighConfidenceOpenFindingsOnly() throws Exception {
        seed(
                finding("job.stale", Category.JOBS, "job", "sure", Confidence.HIGH),
                finding("job.stale", Category.JOBS, "job", "maybe", Confidence.MEDIUM),
                finding("job.stale", Category.JOBS, "job", "unsure", Confidence.LOW));
        var resp = as("admin").goTo(BASE + "removalPlan", null).getWebResponse();
        String plan = resp.getContentAsString();
        assertTrue(plan.contains("name: 'sure'") && !plan.contains("'maybe'") && !plan.contains("'unsure'"), plan);
        assertTrue(resp.getResponseHeaderValue("Content-Disposition").contains("hygiene-removal-plan.groovy"));
        assertTrue(plan.contains("final boolean DRY_RUN = true"));
        assertTrue(as("admin")
                .goTo(BASE + "removalPlan?minConfidence=MEDIUM", null)
                .getWebResponse()
                .getContentAsString()
                .contains("'maybe'"));
        assertTrue(as("admin")
                .goTo(BASE + "removalPlan?minConfidence=LOW", null)
                .getWebResponse()
                .getContentAsString()
                .contains("'unsure'"));
        assertEquals(
                400,
                as("admin")
                        .goTo(BASE + "removalPlan?format=powershell", null)
                        .getWebResponse()
                        .getStatusCode());
        String cli = as("admin")
                .goTo(BASE + "removalPlan?format=cli", null)
                .getWebResponse()
                .getContentAsString();
        assertTrue(cli.contains("# $CLI delete-job 'sure'"));
    }

    @Test
    void generatingAPlanChangesNothing() throws Exception {
        j.createFreeStyleProject("sure");
        seed(finding("job.stale", Category.JOBS, "job", "sure", Confidence.HIGH));
        as("admin").goTo(BASE + "removalPlan", null);
        assertNotNull(j.jenkins.getItem("sure"), "the plugin only writes text, it never deletes");
    }

    @Test
    void dashboardOffersTheDownloads() throws Exception {
        String html = as("admin").goTo(BASE).getWebResponse().getContentAsString();
        assertTrue(html.contains("exportCsv")
                && html.contains("exportJson")
                && html.contains("removalPlan?format=groovy"));
    }

    // -------------------------------------- the generated script, executed

    private String run(String script) {
        StringWriter out = new StringWriter();
        Binding b = new Binding();
        b.setVariable("out", new PrintWriter(out, true));
        new GroovyShell(b).evaluate(script);
        return out.toString();
    }

    @Test
    void theGeneratedScriptDryRunsThenReallyRemovesOnlyWhatIsStillStale() throws Exception {
        System.clearProperty("hygiene.pwned");
        j.createFreeStyleProject("old1");
        Folder f = j.jenkins.createProject(Folder.class, "team");
        f.createProject(FreeStyleProject.class, "old2");
        FreeStyleProject active = j.createFreeStyleProject("active");
        CredentialsProvider.lookupStores(j.jenkins)
                .iterator()
                .next()
                .addCredentials(
                        Domain.global(),
                        new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "dead-cred", "d", "u", "p"));
        DumbSlave agent = j.createSlave("stale-agent", null, null);
        agent.toComputer().setTemporarilyOffline(true, new OfflineCause.ByCLI("gone"));

        List<Finding> findings = new ArrayList<>(List.of(
                finding("job.stale", Category.JOBS, "job", "old1", Confidence.HIGH),
                finding("job.stale", Category.JOBS, "job", "team/old2", Confidence.HIGH),
                finding("job.stale", Category.JOBS, "job", "active", Confidence.HIGH),
                finding("job.stale", Category.JOBS, "job", HOSTILE, Confidence.HIGH),
                finding("credential.unused", Category.CREDENTIALS, "credential", ":_:dead-cred", Confidence.HIGH),
                finding("agent.offline", Category.AGENTS, "agent", "stale-agent", Confidence.HIGH)));
        String script = RemovalPlan.generate(findings, RemovalPlan.Format.GROOVY, "admin", Instant.now());
        j.buildAndAssertSuccess(active); // activity AFTER the plan was generated

        // 1. As generated (DRY_RUN = true): prints, changes nothing.
        String dry = run(script);
        assertTrue(dry.contains("[dry-run] delete job old1"), dry);
        assertTrue(dry.contains("DRY RUN: nothing was changed."), dry);
        assertNotNull(j.jenkins.getItem("old1"));
        assertNotNull(j.jenkins.getItemByFullName("team/old2"));
        assertEquals(1, credentialCount("dead-cred"));
        assertNotNull(j.jenkins.getNode("stale-agent"));

        // 2. After the administrator has reviewed it and flipped the switch.
        String live = run(script.replace("final boolean DRY_RUN = true", "final boolean DRY_RUN = false"));
        assertTrue(live.contains("Removed 4 item(s)"), live);
        assertNull(j.jenkins.getItem("old1"));
        assertNull(j.jenkins.getItemByFullName("team/old2"));
        assertEquals(0, credentialCount("dead-cred"));
        assertNull(j.jenkins.getNode("stale-agent"));
        assertNotNull(j.jenkins.getItem("active"), "built after the plan was generated: skipped, not deleted");
        assertTrue(live.contains("SKIPPED (built after this plan was generated): job active"), live);
        assertTrue(live.contains("already gone: job " + HOSTILE), "hostile name printed verbatim, never executed");
        assertNull(System.getProperty("hygiene.pwned"));

        // 3. Running it again is harmless.
        assertTrue(run(script.replace("final boolean DRY_RUN = true", "final boolean DRY_RUN = false"))
                .contains("Removed 0 item(s)"));
    }

    private int credentialCount(String id) throws Exception {
        int n = 0;
        for (var store : CredentialsProvider.lookupStores(j.jenkins)) {
            for (var d : store.getDomains()) {
                for (var c : store.getCredentials(d)) {
                    if (c instanceof com.cloudbees.plugins.credentials.common.IdCredentials ic
                            && ic.getId().equals(id)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }
}
