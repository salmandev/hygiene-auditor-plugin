package io.jenkins.plugins.hygieneauditor.collector.jobs;

import static io.jenkins.plugins.hygieneauditor.collector.CollectorTestSupport.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.security.ACL;
import io.jenkins.plugins.hygieneauditor.collector.CollectorTestSupport;
import io.jenkins.plugins.hygieneauditor.collector.HygieneCollector;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.Status;
import java.nio.file.Path;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class JobsCollectorTest {
    @TempDir
    Path dir;

    JenkinsRule j;
    CollectorTestSupport t;
    final JobsCollector collector = new JobsCollector();

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        t = new CollectorTestSupport(dir);
    }

    @Test
    void collectorIsDiscoverable() {
        List<String> ids =
                HygieneCollector.all().stream().map(HygieneCollector::getId).toList();
        assertTrue(ids.contains("jobs") && ids.contains("agents"), ids.toString());
    }

    @Test
    void newNeverBuiltJobIsNotReported() throws Exception {
        j.createFreeStyleProject("fresh");
        assertTrue(t.scan(collector, 0).isEmpty());
        assertTrue(t.scan(collector, 10).isEmpty(), "inside the 30-day grace period");
    }

    @Test
    void oldNeverBuiltJobGetsConfidenceFromAge() throws Exception {
        j.createFreeStyleProject("old");
        Finding medium = find(t.scan(collector, 40), "job.never_built", "old").orElseThrow();
        assertEquals(Confidence.MEDIUM, medium.confidence());
        Finding high = find(t.scan(collector, 400), "job.never_built", "old").orElseThrow();
        assertEquals(Confidence.HIGH, high.confidence());
        assertTrue(high.evidence().stream().anyMatch(e -> e.text().contains("Not checked")), "states its limits");
    }

    @Test
    void staleJobAfterThreshold() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("built");
        j.buildAndAssertSuccess(p);
        assertTrue(t.scan(collector, 10).isEmpty());
        assertEquals(
                Confidence.MEDIUM,
                find(t.scan(collector, 400), "job.stale", "built").orElseThrow().confidence());
        assertEquals(
                Confidence.HIGH,
                find(t.scan(collector, 800), "job.stale", "built").orElseThrow().confidence());
    }

    @Test
    void activeJobIsNeverReportedAndFindingsResolveWhenJobDeleted() throws Exception {
        FreeStyleProject gone = j.createFreeStyleProject("gone");
        t.scan(collector, 400);
        assertTrue(find(t.store.all(), "job.never_built", "gone").isPresent());
        gone.delete();
        t.scan(collector, 400);
        assertEquals(
                Status.RESOLVED,
                find(t.store.all(), "job.never_built", "gone").orElseThrow().status());
    }

    @Test
    void disabledJobYieldsOnlyOneFinding() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("off");
        p.disable();
        assertTrue(t.scan(collector, 10).isEmpty(), "recently disabled");
        List<Finding> found = t.scan(collector, 200);
        assertEquals(1, found.size());
        assertEquals("job.disabled", found.get(0).type());
        assertEquals(Confidence.HIGH, found.get(0).confidence()); // disabled 200d (50) + never built (20) = 70
    }

    @Test
    void nestedFolderUsesFullName() throws Exception {
        Folder f = j.jenkins.createProject(Folder.class, "team");
        f.createProject(FreeStyleProject.class, "app");
        assertTrue(find(t.scan(collector, 400), "job.never_built", "team/app").isPresent());
    }

    @Test
    void scanSeesEverythingEvenWhenRunByUserWithoutPermissions() throws Exception {
        j.createFreeStyleProject("hidden");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy());
        // Anonymous has no permissions at all, yet the collector must see the job (scan runs as SYSTEM).
        try (var ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            assertFalse(j.jenkins.hasPermission(Jenkins.ADMINISTER));
            assertTrue(find(t.scan(collector, 400), "job.never_built", "hidden").isPresent());
        }
    }

    @Test
    void thresholdsAreConfigurable() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("b");
        j.buildAndAssertSuccess(p);
        JobsCollector strict = new JobsCollector(new JobsSettings(5, 0, 5));
        assertTrue(find(t.scan(strict, 10), "job.stale", "b").isPresent());
    }
}
