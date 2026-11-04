package io.jenkins.plugins.hygieneauditor.policy;

import static io.jenkins.plugins.hygieneauditor.collector.CollectorTestSupport.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration;
import io.jenkins.plugins.hygieneauditor.collector.CollectorTestSupport;
import io.jenkins.plugins.hygieneauditor.collector.jobs.JobsCollector;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.scan.ScanRecord;
import io.jenkins.plugins.hygieneauditor.scan.ScanRequest;
import io.jenkins.plugins.hygieneauditor.scan.ScanService;
import io.jenkins.plugins.hygieneauditor.store.FindingStoreHolder;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PolicyAndThresholdScanTest {
    @TempDir
    Path dir;

    private static IgnoreRule rule(String category, String subject, String folder) {
        IgnoreRule r = new IgnoreRule(category);
        r.setSubject(subject);
        r.setFolder(folder);
        return r;
    }

    private ScanRecord scan(Category... cats) throws Exception {
        return ScanService.get()
                .start(new ScanRequest(Set.of(cats), "test"))
                .result()
                .get(60, TimeUnit.SECONDS);
    }

    @Test
    void ignoreRulesKeepMatchingFindingsOutOfTheStoreAndAreCounted(JenkinsRule j) throws Exception {
        HygieneAuditorConfiguration cfg = HygieneAuditorConfiguration.get();
        cfg.setNeverBuiltGraceDays(0);
        Folder sandbox = j.jenkins.createProject(Folder.class, "sandbox");
        sandbox.createProject(FreeStyleProject.class, "scratch");
        j.createFreeStyleProject("keepme");
        var store = CredentialsProvider.lookupStores(j.jenkins).iterator().next();
        store.addCredentials(
                Domain.global(),
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "prod-key", "d", "u", "p"));
        store.addCredentials(
                Domain.global(),
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "dev-key", "d", "u", "p"));

        // Baseline without rules (the clock cannot be moved through the service, so use grace 0 for jobs).
        ScanRecord before = scan(Category.JOBS, Category.CREDENTIALS);
        List<String> subjects = FindingStoreHolder.get().store().all().stream()
                .map(f -> f.subject().fullName())
                .toList();
        assertTrue(
                subjects.contains("sandbox/scratch") && subjects.contains("keepme"),
                "baseline has both jobs: " + subjects);
        assertTrue(subjects.contains(":_:prod-key") && subjects.contains(":_:dev-key"), subjects.toString());
        assertEquals(0, before.exemptedByPolicy());

        cfg.setIgnoreRules(List.of(rule("CREDENTIALS", "^prod-.*", null), rule(null, null, "sandbox")));
        ScanRecord after = scan(Category.JOBS, Category.CREDENTIALS);
        assertTrue(after.exemptedByPolicy() >= 2, "prod-key and anything in sandbox: " + after.exemptedByPolicy());
        List<Finding> open = FindingStoreHolder.get().store().all().stream()
                .filter(f -> f.status() == io.jenkins.plugins.hygieneauditor.model.Status.OPEN)
                .toList();
        assertTrue(find(open, "credential.unused", ":_:dev-key").isPresent(), "not exempt");
        assertFalse(find(open, "credential.unused", ":_:prod-key").isPresent(), "exempt by rule");
        assertTrue(open.stream().noneMatch(f -> f.subject().fullName().startsWith("sandbox/")), "folder exempt");
    }

    @Test
    void thresholdsComeFromTheGlobalConfiguration(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("built");
        j.buildAndAssertSuccess(p);
        CollectorTestSupport t = new CollectorTestSupport(dir);
        JobsCollector collector = new JobsCollector(); // reads the configuration at scan time
        assertTrue(t.scan(collector, 2).isEmpty(), "default threshold is 365 days");
        HygieneAuditorConfiguration.get().setStaleJobDays(1);
        assertTrue(find(t.scan(collector, 2), "job.stale", "built").isPresent());
        HygieneAuditorConfiguration.get().setStaleJobDays(30);
        assertTrue(t.scan(collector, 2).isEmpty(), "and back again, without a restart");
    }

    @Test
    void retentionLimitsFollowTheConfigurationWithoutRestart(JenkinsRule j) throws Exception {
        HygieneAuditorConfiguration.get().setMaxFindings(1);
        var store = FindingStoreHolder.get().store();
        assertEquals(1, HygieneAuditorConfiguration.get().retentionPolicy().maxFindings());
        j.createFreeStyleProject("a");
        j.createFreeStyleProject("b");
        HygieneAuditorConfiguration.get().setNeverBuiltGraceDays(0);
        scan(Category.JOBS);
        assertEquals(1, store.all().size(), "capped at the configured maximum");
    }

    @Test
    void disabledCollectorIsNotRunByTheService(JenkinsRule j) throws Exception {
        HygieneAuditorConfiguration.get().setDisabledCollectors(List.of("jobs"));
        j.createFreeStyleProject("x");
        HygieneAuditorConfiguration.get().setNeverBuiltGraceDays(0);
        ScanRecord r = scan(Category.JOBS, Category.AGENTS);
        assertTrue(r.outcomes().stream().noneMatch(o -> o.collectorId().equals("jobs")));
        assertTrue(FindingStoreHolder.get().store().all().isEmpty());
    }

    @Test
    void everyScanAddsAHistoryEntryWithCounts(JenkinsRule j) throws Exception {
        HygieneAuditorConfiguration.get().setNeverBuiltGraceDays(0);
        j.createFreeStyleProject("one");
        scan(Category.JOBS);
        j.createFreeStyleProject("two");
        scan(Category.JOBS);
        var history = FindingStoreHolder.get().history().all();
        assertEquals(2, history.size());
        assertEquals(1, history.get(0).totalOpen());
        assertEquals(2, history.get(1).totalOpen());
        assertEquals("COMPLETED", history.get(1).state());
    }
}
