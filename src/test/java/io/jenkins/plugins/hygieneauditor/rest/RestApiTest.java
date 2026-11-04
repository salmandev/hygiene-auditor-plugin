package io.jenkins.plugins.hygieneauditor.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import io.jenkins.plugins.hygieneauditor.scan.ScanRequest;
import io.jenkins.plugins.hygieneauditor.scan.ScanService;
import io.jenkins.plugins.hygieneauditor.store.FindingStoreHolder;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class RestApiTest {
    static final String BASE = "manage/hygiene-auditor/";
    JenkinsRule j;
    Finding jobMedium;
    Finding jobHigh;
    Finding agentLow;

    private static Finding finding(String type, Category cat, String kind, String name, Severity s, Confidence c) {
        return Finding.observed(
                new FindingCandidate(
                        type,
                        cat,
                        Subject.of(kind, name, null),
                        s,
                        c,
                        List.of(new Evidence("t", "evidence for " + name, 10)),
                        "act"),
                "col",
                Instant.now());
    }

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.createFreeStyleProject("p1");
        j.createFreeStyleProject("p2");
        jobMedium = finding("job.stale", Category.JOBS, "job", "p1", Severity.LOW, Confidence.MEDIUM);
        jobHigh = finding("job.stale", Category.JOBS, "job", "p2", Severity.MEDIUM, Confidence.HIGH);
        agentLow = finding("agent.offline", Category.AGENTS, "agent", "n1", Severity.INFO, Confidence.LOW);
        FindingStoreHolder.get().store().recordScan(List.of(jobMedium, jobHigh, agentLow), Set.of(), Instant.now());

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ, Item.READ, HygieneAuditorPermissions.VIEW)
                .everywhere()
                .to("viewer")
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("nobody"));
    }

    private JenkinsRule.WebClient client(String user) {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return user == null ? wc : wc.withBasicCredentials(user);
    }

    private JsonObject get(String user, String path) throws Exception {
        var page = client(user).goTo(path, "application/json");
        assertEquals(200, page.getWebResponse().getStatusCode(), path);
        return JsonParser.parseString(page.getWebResponse().getContentAsString())
                .getAsJsonObject();
    }

    private int status(String user, String path, HttpMethod method) throws Exception {
        JenkinsRule.WebClient wc = client(user);
        return wc.getPage(wc.addCrumb(new WebRequest(new URL(j.getURL(), path), method)))
                .getWebResponse()
                .getStatusCode();
    }

    @Test
    void summaryCountsOpenFindings() throws Exception {
        JsonObject o = get("admin", BASE + "api/json");
        assertEquals(2, o.getAsJsonObject("openByCategory").get("JOBS").getAsInt());
        assertEquals(1, o.getAsJsonObject("openByCategory").get("AGENTS").getAsInt());
        assertEquals(1, o.getAsJsonObject("openBySeverity").get("MEDIUM").getAsInt());
        assertEquals(3, o.getAsJsonObject("byStatus").get("OPEN").getAsInt());
        assertFalse(o.get("scanRunning").getAsBoolean());
    }

    @Test
    void listFiltersByCategoryConfidenceAndStatus() throws Exception {
        JsonObject all = get("admin", BASE + "findings/api/json");
        assertEquals(3, all.get("total").getAsInt());
        JsonArray first = all.getAsJsonArray("findings");
        assertEquals(jobHigh.id(), first.get(0).getAsJsonObject().get("id").getAsString(), "worst first");

        JsonObject jobs = get("admin", BASE + "findings/api/json?category=JOBS&minConfidence=HIGH");
        assertEquals(1, jobs.get("total").getAsInt());
        assertEquals(
                "p2",
                jobs.getAsJsonArray("findings")
                        .get(0)
                        .getAsJsonObject()
                        .get("subject")
                        .getAsString());

        assertEquals(
                0,
                get("admin", BASE + "findings/api/json?status=RESOLVED")
                        .get("total")
                        .getAsInt());
        assertEquals(
                3,
                get("admin", BASE + "findings/api/json?status=all").get("total").getAsInt());
    }

    @Test
    void pagingAndTreeFilter() throws Exception {
        JsonObject page = get("admin", BASE + "findings/api/json?limit=1&offset=1");
        assertEquals(3, page.get("total").getAsInt());
        assertEquals(1, page.getAsJsonArray("findings").size());
        JsonObject slim = get("admin", BASE + "findings/api/json?tree=total,findings[id,severity]");
        JsonObject f = slim.getAsJsonArray("findings").get(0).getAsJsonObject();
        assertEquals(Set.of("id", "severity"), f.keySet());
    }

    @Test
    void badQueryParameterIs400() throws Exception {
        assertEquals(
                400,
                client("admin")
                        .goTo(BASE + "findings/api/json?minConfidence=SORTA", null)
                        .getWebResponse()
                        .getStatusCode());
        assertEquals(
                400,
                client("admin")
                        .goTo(BASE + "findings/api/json?limit=abc", null)
                        .getWebResponse()
                        .getStatusCode());
    }

    @Test
    void detailShowsEvidenceAndUnknownIdIs404() throws Exception {
        JsonObject d = get("admin", BASE + "finding/" + jobMedium.id() + "/api/json");
        assertEquals("job.stale", d.get("type").getAsString());
        assertEquals(
                "evidence for p1",
                d.getAsJsonArray("evidence")
                        .get(0)
                        .getAsJsonObject()
                        .get("text")
                        .getAsString());
        assertEquals("OPEN", d.get("status").getAsString());
        assertEquals(
                404,
                client("admin")
                        .goTo(BASE + "finding/nope/api/json", null)
                        .getWebResponse()
                        .getStatusCode());
    }

    private void assertDenied(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = client(user);
        wc.getOptions().setRedirectEnabled(false);
        var r = wc.goTo(path, null).getWebResponse();
        String body = r.getContentAsString();
        assertTrue(
                r.getStatusCode() == 403
                        || r.getStatusCode() == 302
                        || (r.getStatusCode() == 200 && body.contains("j_username")),
                user + " " + path + " -> " + r.getStatusCode() + " (only a refusal or the login form is acceptable)");
        assertFalse(r.getContentAsString().contains("\"openByCategory\""), "no data may leak");
        assertFalse(r.getContentType().contains("json"), user + " " + path);
    }

    @Test
    void anonymousAndUsersWithoutViewAreRefused() throws Exception {
        for (String path : List.of(
                BASE + "api/json", BASE + "findings/api/json", BASE + "finding/" + jobMedium.id() + "/api/json")) {
            assertDenied(null, path);
            assertDenied("nobody", path);
        }
    }

    @Test
    void viewerSeesJobsButNotGlobalCategories() throws Exception {
        JsonObject all = get("viewer", BASE + "findings/api/json");
        assertEquals(2, all.get("total").getAsInt());
        assertEquals(
                404,
                client("viewer")
                        .goTo(BASE + "finding/" + agentLow.id() + "/api/json", null)
                        .getWebResponse()
                        .getStatusCode(),
                "a finding the user may not see looks exactly like one that does not exist");
    }

    @Test
    void actionsRequirePostAndScanPermission() throws Exception {
        for (String action : List.of("scan", "cancel", "setStatus")) {
            assertEquals(405, status("admin", BASE + action, HttpMethod.GET), action + " must reject GET");
            assertEquals(403, status("viewer", BASE + action, HttpMethod.POST), action + " needs SCAN");
        }
    }

    @Test
    void adminCanIgnoreAcknowledgeAndReopen() throws Exception {
        String base = BASE + "setStatus?id=" + jobMedium.id();
        assertEquals(400, status("admin", base + "&status=IGNORED", HttpMethod.POST), "ignore needs a reason");
        assertEquals(400, status("admin", base + "&status=RESOLVED", HttpMethod.POST), "users cannot resolve");
        assertEquals(400, status("admin", base + "&status=BOGUS", HttpMethod.POST));
        assertEquals(404, status("admin", BASE + "setStatus?id=nope&status=OPEN", HttpMethod.POST));
        assertEquals(
                200,
                status("admin", base + "&status=IGNORED&reason=planned+decommission&snoozeDays=30", HttpMethod.POST));
        Finding f = FindingStoreHolder.get().store().get(jobMedium.id()).orElseThrow();
        assertEquals(Status.IGNORED, f.status());
        assertEquals("admin", f.statusChangedBy());
        assertTrue(f.snoozeUntil().isAfter(Instant.now().plusSeconds(29L * 86400)));
        assertEquals(
                2,
                get("admin", BASE + "findings/api/json").get("total").getAsInt(),
                "ignored findings are hidden by default");
        assertEquals(200, status("admin", base + "&status=ACKNOWLEDGED", HttpMethod.POST));
        assertEquals(200, status("admin", base + "&status=OPEN", HttpMethod.POST));
    }

    @Test
    void manualScanRunsAndRejectsOverlap() throws Exception {
        assertEquals(202, status("admin", BASE + "scan?categories=AGENTS", HttpMethod.POST));
        ScanService.get().history(); // scan runs in the background
        for (int i = 0; i < 100 && ScanService.get().lastScan() == null; i++) {
            Thread.sleep(100);
        }
        assertEquals("manual", ScanService.get().lastScan().trigger());
        assertEquals(400, status("admin", BASE + "scan?categories=NONSENSE", HttpMethod.POST));
    }

    @Test
    void noSecretsInAnyResponseEvenForRealCredentialFindings() throws Exception {
        String password = "Zx9-secret-PASSWORD-value";
        String user = "svc-restuser";
        UsernamePasswordCredentialsImpl c = new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, "orphan-cred", "token=desc-secret-77", user, password);
        CredentialsProvider.lookupStores(j.jenkins).iterator().next().addCredentials(Domain.global(), c);
        ScanService.get()
                .start(new ScanRequest(Set.of(Category.CREDENTIALS), "test"))
                .result()
                .get(60, TimeUnit.SECONDS);

        StringBuilder everything = new StringBuilder();
        for (String path :
                List.of(BASE + "api/json", BASE + "findings/api/json?status=all", BASE + "findings/api/json?depth=2")) {
            everything.append(client("admin").goTo(path, null).getWebResponse().getContentAsString());
        }
        String id = FindingStoreHolder.get().store().all().stream()
                .filter(f -> f.category() == Category.CREDENTIALS)
                .findFirst()
                .orElseThrow()
                .id();
        everything.append(client("admin")
                .goTo(BASE + "finding/" + id + "/api/json?depth=3", null)
                .getWebResponse()
                .getContentAsString());
        assertTrue(everything.toString().contains("orphan-cred"), "the finding itself is there");
        for (String forbidden : List.of(password, user, "desc-secret-77")) {
            assertFalse(everything.toString().contains(forbidden), "leaked: " + forbidden);
        }
    }
}
