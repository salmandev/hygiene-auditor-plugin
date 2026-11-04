package io.jenkins.plugins.hygieneauditor.collector.plugins;

import static io.jenkins.plugins.hygieneauditor.collector.CollectorTestSupport.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.PluginWrapper;
import hudson.diagnosis.OldDataMonitor;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorPermissions;
import io.jenkins.plugins.hygieneauditor.collector.CollectorTestSupport;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.scan.CollectorOutcome;
import io.jenkins.plugins.hygieneauditor.scan.ScanSettings;
import io.jenkins.plugins.hygieneauditor.security.FindingAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PluginsCollectorTest {
    @TempDir
    Path dir;

    JenkinsRule j;
    CollectorTestSupport t;
    final PluginsCollector collector = new PluginsCollector();

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        t = new CollectorTestSupport(dir);
    }

    private List<Finding> scan() throws Exception {
        return t.scan(collector, 0);
    }

    private static List<Finding> reviews(List<Finding> all) {
        return all.stream().filter(f -> f.type().equals("plugin.needs_review")).toList();
    }

    private void plantOrphan(String jobName) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(jobName);
        Path cfg = p.getConfigFile().getFile().toPath();
        Files.writeString(
                cfg,
                Files.readString(cfg)
                        .replace(
                                "<builders/>",
                                "<builders><com.example.gone.GoneBuilder plugin=\"gone@1.0\"><x>1</x></com.example.gone.GoneBuilder></builders>"));
        j.jenkins.reload();
    }

    // ------------------------------------------------------- orphaned data

    @Test
    void dataJenkinsCannotReadIsReportedWithTheMissingPluginNamed() throws Exception {
        plantOrphan("orphan");
        Finding f = find(scan(), "plugin.orphaned_data", "orphan").orElseThrow();
        assertEquals("plugin_data", f.subject().kind());
        assertEquals(Confidence.MEDIUM, f.confidence()); // Jenkins' own diagnosis, but also caused by plugin upgrades
        assertEquals(Severity.LOW, f.severity());
        String text = f.evidence().toString();
        assertTrue(text.contains("CannotResolveClassException: com.example.gone.GoneBuilder"), text);
        assertTrue(text.contains("not installed: gone"), "names the missing plugin from the config stamp: " + text);
        assertTrue(text.contains("upgrade removed a class"), "states the other possible cause: " + text);
        assertTrue(text.contains("stays on disk"), text);
    }

    @Test
    void dataFromANewerVersionIsNotOrphanedData() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("future");
        OldDataMonitor.report(p, "99.0"); // records a version only, no unreadable-data message
        assertTrue(find(scan(), "plugin.orphaned_data", "future").isEmpty());
    }

    @Test
    void aCleanInstanceHasNoOrphanedData() throws Exception {
        assertTrue(scan().stream().noneMatch(f -> f.type().equals("plugin.orphaned_data")));
    }

    // -------------------------------------------------------- needs review

    @Test
    void neverClaimsMoreThanLowConfidenceAndInfoSeverity() throws Exception {
        List<Finding> found = reviews(scan());
        assertFalse(found.isEmpty(), "a stock test instance has leaf plugins with no footprint");
        for (Finding f : found) {
            assertEquals(Confidence.LOW, f.confidence(), f.subject().fullName());
            assertEquals(Severity.INFO, f.severity(), f.subject().fullName());
            String text = f.evidence().toString();
            assertTrue(text.contains("question for whoever installed it"), "states it is not a verdict: " + text);
            assertTrue(text.contains("Not checked"), text);
        }
    }

    @Test
    void neverReportsLibrariesSomethingNeedsBundledDisabledOrItself() throws Exception {
        Set<String> flagged = new java.util.TreeSet<>();
        reviews(scan()).forEach(f -> flagged.add(f.subject().fullName()));
        assertFalse(flagged.contains("hygiene-auditor"), "ourselves");
        for (PluginWrapper p : j.jenkins.getPluginManager().getPlugins()) {
            if (!p.getDependents().isEmpty() || p.isBundled() || !p.isEnabled()) {
                assertFalse(flagged.contains(p.getShortName()), p.getShortName() + " must not be flagged");
            }
        }
        assertFalse(flagged.contains("credentials") || flagged.contains("scm-api"), "libraries other plugins need");
    }

    @Test
    void aConfigurationStampMakesAPluginObserved() throws Exception {
        List<Finding> before = reviews(scan());
        String victim = before.get(0).subject().fullName();
        FreeStyleProject p = j.createFreeStyleProject("uses-it");
        Path cfg = p.getConfigFile().getFile().toPath();
        Files.writeString(
                cfg, Files.readString(cfg).replace("</project>", "<note plugin=\"" + victim + "@1.0\"/></project>"));
        List<Finding> after = reviews(scan());
        assertTrue(find(before, "plugin.needs_review", victim).isPresent());
        assertTrue(
                find(after, "plugin.needs_review", victim).isEmpty(), victim + " is now referenced by a configuration");
        assertEquals(before.size() - 1, after.size(), "and nothing else changed");
    }

    @Test
    void rootSettingsFilesAreRecognisedByTheClassTheyAreNamedAfter() throws Exception {
        // Which plugin owns the class is Jenkins' call (and cannot be shown here: this test classpath also contains
        // the plugin jars, so classes are not loaded by the plugin class loaders). The naming rule is ours.
        assertEquals(
                "hudson.plugins.git.GitTool",
                PluginsCollector.classNameOf(Path.of("/jh/hudson.plugins.git.GitTool.xml")));
        assertEquals(
                "jenkins.model.JenkinsLocationConfiguration",
                PluginsCollector.classNameOf(Path.of("jenkins.model.JenkinsLocationConfiguration.xml")));
        for (String notAClass : List.of(
                "config.xml",
                "nodeMonitors.xml",
                "credentials.xml",
                "x.y.xml.bak",
                "../../etc/passwd.xml",
                "a b.c.xml")) {
            assertEquals(null, PluginsCollector.classNameOf(Path.of("/jh").resolve(notAClass)), notAClass);
        }
        assertTrue(
                Files.exists(j.jenkins.getRootDir().toPath().resolve("hudson.plugins.git.GitTool.xml")),
                "the premise: a real instance does persist such a file");
    }

    @Test
    void stepsOfRecentPipelineRunsMapBackToTheirPlugin() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "pipe");
        p.setDefinition(new CpsFlowDefinition("echo 'hi'\nsleep 0\n", true));
        j.buildAndAssertSuccess(p);
        assertEquals(Set.of("workflow-basic-steps"), PipelineStepPlugins.of(p));
        assertEquals(Set.of(), PipelineStepPlugins.of(j.createFreeStyleProject("not-a-pipeline")));
        WorkflowJob never = j.createProject(WorkflowJob.class, "never-ran");
        assertEquals(Set.of(), PipelineStepPlugins.of(never));
    }

    @Test
    void anUnreadableConfigurationIsAdmittedInTheEvidenceAndDoesNotHangOrCrash() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("hostile");
        Files.writeString(
                p.getConfigFile().getFile().toPath(),
                "<?xml version='1.0'?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><project>&e;</project>");
        List<Finding> found = assertTimeoutPreemptively(Duration.ofSeconds(60), this::scan);
        assertEquals(CollectorOutcome.Status.COMPLETE, t.last.outcomes().get(0).status());
        assertTrue(reviews(found).stream().allMatch(f -> f.evidence().toString().contains("could not be read")));
        assertTrue(found.toString().indexOf("root:") < 0, "the external entity was never resolved");
    }

    @Test
    void aCutShortScanEmitsNothing() throws Exception {
        plantOrphan("orphan");
        for (int i = 0; i < 10; i++) {
            j.createFreeStyleProject("job" + i);
        }
        ScanSettings tiny = new ScanSettings(Set.of(), Duration.ofMinutes(1), 5, 100);
        assertTrue(t.scan(collector, 0, tiny).isEmpty());
        assertEquals(CollectorOutcome.Status.PARTIAL, t.last.outcomes().get(0).status());
    }

    // ------------------------------------------------------------ access

    @Test
    void pluginFindingsAreForAdministratorsOnly() throws Exception {
        plantOrphan("orphan");
        scan();
        assertFalse(t.store.all().isEmpty());
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ, Item.READ, HygieneAuditorPermissions.VIEW)
                .everywhere()
                .to("viewer"));
        try (ACLContext ignored = ACL.as2(User.getById("viewer", true).impersonate2())) {
            assertTrue(FindingAccess.filter(t.store.all()).isEmpty());
        }
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertEquals(
                    t.store.all().size(), FindingAccess.filter(t.store.all()).size());
        }
    }

    @Test
    void collectorIsRegisteredAndApplicable() {
        assertNotNull(ExtensionList.lookupSingleton(PluginsCollector.class));
        assertTrue(collector.isApplicable());
        assertEquals("plugins", collector.getId());
    }
}
