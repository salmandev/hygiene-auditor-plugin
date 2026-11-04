package io.jenkins.plugins.hygieneauditor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorPermissions;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import io.jenkins.plugins.hygieneauditor.store.FindingStoreHolder;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The reason VIEW is grantable on a folder: a team lead sees their folder's findings and nothing else. */
@WithJenkins
class FolderPageTest {
    JenkinsRule j;
    Finding jobA;
    Finding credA;
    Finding jobB;
    Finding agent;

    private static Finding finding(Category cat, String type, String kind, String name) {
        return Finding.observed(
                new FindingCandidate(
                        type,
                        cat,
                        Subject.of(kind, name, null),
                        Severity.LOW,
                        Confidence.MEDIUM,
                        List.of(new Evidence("t", "evidence of " + name, 10)),
                        "act"),
                "c",
                Instant.now());
    }

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        Folder a = j.jenkins.createProject(Folder.class, "a");
        Folder b = j.jenkins.createProject(Folder.class, "b");
        a.createProject(FreeStyleProject.class, "j1");
        b.createProject(FreeStyleProject.class, "j1");
        jobA = finding(Category.JOBS, "job.stale", "job", "a/j1");
        credA = finding(Category.CREDENTIALS, "credential.unused", "credential", "a:_:cred-a");
        jobB = finding(Category.JOBS, "job.stale", "job", "b/j1");
        agent = finding(Category.AGENTS, "agent.offline", "agent", "node1");
        FindingStoreHolder.get().store().recordScan(List.of(jobA, credA, jobB, agent), Set.of(), Instant.now());

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("lead")
                .grant(Item.READ, HygieneAuditorPermissions.VIEW, CredentialsProvider.VIEW)
                .onFolders(a)
                .to("lead"));
    }

    private JenkinsRule.WebClient as(String user) {
        JenkinsRule.WebClient wc = j.createWebClient().withBasicCredentials(user);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return wc;
    }

    private JsonObject json(String user, String path) throws Exception {
        var r = as(user).goTo(path, null).getWebResponse();
        assertEquals(200, r.getStatusCode(), path);
        return JsonParser.parseString(r.getContentAsString()).getAsJsonObject();
    }

    @Test
    void teamLeadSeesOnlyTheirFolder() throws Exception {
        HtmlPage page = as("lead").goTo("job/a/hygiene-auditor/");
        String text = page.asNormalizedText();
        assertTrue(text.contains("a/j1") && text.contains("cred-a"), text);
        assertFalse(text.contains("b/j1") || text.contains("node1"), "other folders and global findings stay hidden");
        assertTrue(text.contains("1–2 / 2"));
        assertTrue(page.getForms().stream().noneMatch(f -> "scan".equals(f.getNameAttribute())), "read-only");
    }

    @Test
    void apiIsScopedTheSameWayAndCannotBeWidenedByTheClient() throws Exception {
        JsonObject own = json("lead", "job/a/hygiene-auditor/findings/api/json");
        assertEquals(2, own.get("total").getAsInt());
        JsonObject widened = json("lead", "job/a/hygiene-auditor/findings/api/json?folder=b&category=AGENTS");
        assertEquals(
                0,
                widened.get("total").getAsInt(),
                "client-supplied folder is overridden; AGENTS are not visible anyway");
        JsonObject b = json("lead", "job/a/hygiene-auditor/findings/api/json?folder=b");
        assertEquals(2, b.get("total").getAsInt(), "still folder a, whatever the client asks");
    }

    @Test
    void theLeadCannotReachOtherFoldersOrTheGlobalPage() throws Exception {
        assertEquals(
                404,
                as("lead").goTo("job/b/hygiene-auditor/", null).getWebResponse().getStatusCode());
        var global = as("lead").goTo("manage/hygiene-auditor/api/json", null).getWebResponse();
        assertFalse(global.getContentType().contains("json"), "no global data");
    }

    @Test
    void findingDetailIsRestrictedToTheFolder() throws Exception {
        assertEquals(
                200,
                as("lead")
                        .goTo("job/a/hygiene-auditor/finding/" + jobA.id() + "/api/json", null)
                        .getWebResponse()
                        .getStatusCode());
        assertEquals(
                404,
                as("lead")
                        .goTo("job/a/hygiene-auditor/finding/" + jobB.id() + "/api/json", null)
                        .getWebResponse()
                        .getStatusCode(),
                "a finding of another folder, even via this folder's URL");
        assertEquals(
                404,
                as("lead")
                        .goTo("job/a/hygiene-auditor/finding/" + agent.id() + "/api/json", null)
                        .getWebResponse()
                        .getStatusCode());
        HtmlPage detail = as("lead").goTo("job/a/hygiene-auditor/finding/" + jobA.id() + "/");
        assertTrue(detail.asNormalizedText().contains("evidence of a/j1"));
        assertTrue(
                detail.getForms().stream().noneMatch(f -> "status".equals(f.getNameAttribute())), "no decisions here");
    }

    @Test
    void folderPageIsLinkedFromTheFolderAndAdminsSeeEveryFolder() throws Exception {
        assertTrue(as("lead").goTo("job/a/").asXml().contains("hygiene-auditor"), "sidebar entry");
        assertFalse(
                as("admin").goTo("job/b/hygiene-auditor/").asNormalizedText().contains("a/j1"));
        assertTrue(as("admin").goTo("job/b/hygiene-auditor/").asNormalizedText().contains("b/j1"));
    }
}
