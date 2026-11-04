package io.jenkins.plugins.hygieneauditor.collector.credentials;

import static io.jenkins.plugins.hygieneauditor.collector.CollectorTestSupport.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import io.jenkins.plugins.hygieneauditor.collector.CollectorTestSupport;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.scan.CollectorOutcome;
import io.jenkins.plugins.hygieneauditor.scan.ScanSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CredentialsCollectorTest {
    static final String PASSWORD = "S3cr3t-Hunter2-value";
    static final String USERNAME = "svc-adminuser";
    static final String DESCRIPTION = "prod deploy token=descsecret99";

    @TempDir
    Path dir;

    JenkinsRule j;
    CollectorTestSupport t;
    final CredentialsCollector collector = new CredentialsCollector();

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        t = new CollectorTestSupport(dir);
    }

    @AfterEach
    void clearProps() {
        System.clearProperty("CredentialsProvider.fingerprintEnabled");
        System.clearProperty("casc.jenkins.config");
    }

    private UsernamePasswordCredentialsImpl add(Object context, String id) throws Exception {
        UsernamePasswordCredentialsImpl c =
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, id, DESCRIPTION, USERNAME, PASSWORD);
        CredentialsStore store = CredentialsProvider.lookupStores((hudson.model.ModelObject) context)
                .iterator()
                .next();
        store.addCredentials(Domain.global(), c);
        return c;
    }

    private WorkflowJob pipeline(String name, String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, name);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p;
    }

    private static String root(String id) {
        return ":_:" + id;
    }

    @Test
    void applicable() {
        assertTrue(collector.isApplicable());
    }

    @Test
    void unusedCredentialIsFlaggedAndUsedOneIsNot() throws Exception {
        add(j.jenkins, "used-one");
        add(j.jenkins, "orphan");
        pipeline("deploy", "git url: 'https://example.invalid/r.git', credentialsId: 'used-one'");

        List<Finding> found = t.scan(collector, 0);
        assertTrue(find(found, "credential.unused", root("used-one")).isEmpty());
        Finding f = find(found, "credential.unused", root("orphan")).orElseThrow();
        assertEquals(Confidence.MEDIUM, f.confidence()); // static 45 + tracking-on-but-never-seen 20
        assertEquals(Severity.MEDIUM, f.severity());
        assertTrue(f.evidence().stream().anyMatch(e -> e.text().contains("No reference to this id")));
        assertTrue(f.evidence().stream().anyMatch(e -> e.text().contains("Not scanned")), "states its limits");
        assertEquals(CollectorOutcome.Status.COMPLETE, t.last.outcomes().get(0).status());
    }

    @Test
    void unscannableSourcesWouldShowUp_environmentIsClean() throws Exception {
        // Guards the other tests: in a stock test instance every XML file must be parseable (incl. XML 1.1).
        add(j.jenkins, "x");
        t.scan(collector, 0);
        Finding f = find(t.store.all(), "credential.unused", root("x")).orElseThrow();
        assertFalse(f.evidence().stream().anyMatch(e -> e.text().contains("could not be read")), f.toString());
    }

    @Test
    void dynamicReferenceLowersConfidenceAndSaysWhy() throws Exception {
        add(j.jenkins, "maybe");
        pipeline("generic", "git url: 'u', credentialsId: params.CRED");
        Finding f =
                find(t.scan(collector, 0), "credential.unused", root("maybe")).orElseThrow();
        assertEquals(Confidence.LOW, f.confidence());
        assertTrue(f.evidence().stream().anyMatch(e -> e.text().contains("dynamic credential reference")));
    }

    @Test
    void dynamicReferenceOnlyAffectsCredentialsInItsScope() throws Exception {
        Folder a = j.jenkins.createProject(Folder.class, "a");
        Folder b = j.jenkins.createProject(Folder.class, "b");
        add(a, "cred-a");
        add(b, "cred-b");
        a.createProject(WorkflowJob.class, "job")
                .setDefinition(new CpsFlowDefinition("git credentialsId: params.X", true));
        List<Finding> found = t.scan(collector, 0);
        assertEquals(
                Confidence.LOW,
                find(found, "credential.unused", "a:_:cred-a").orElseThrow().confidence());
        assertEquals(
                Confidence.MEDIUM,
                find(found, "credential.unused", "b:_:cred-b").orElseThrow().confidence());
    }

    @Test
    void folderCredentialDefinitionInsideFolderConfigIsNotMistakenForAUse() throws Exception {
        Folder f = j.jenkins.createProject(Folder.class, "team");
        add(f, "folder-cred");
        assertTrue(
                Files.readString(f.getConfigFile().getFile().toPath()).contains("folder-cred"),
                "precondition: the id really is in the folder's config.xml");
        assertTrue(find(t.scan(collector, 0), "credential.unused", "team:_:folder-cred")
                .isPresent());
    }

    @Test
    void aRootCredentialIsReportedOnceNotOncePerFolder() throws Exception {
        // Found by running the plugin in Docker: with folders present, inherited stores were counted under each folder.
        add(j.jenkins, "root-orphan");
        Folder a = j.jenkins.createProject(Folder.class, "a");
        j.jenkins.createProject(Folder.class, "b");
        add(a, "folder-orphan");
        List<Finding> found = t.scan(collector, 0);
        assertEquals(
                List.of(":_:root-orphan"),
                found.stream()
                        .map(f -> f.subject().fullName())
                        .filter(n -> n.endsWith("root-orphan"))
                        .toList());
        assertEquals(
                List.of("a:_:folder-orphan"),
                found.stream()
                        .map(f -> f.subject().fullName())
                        .filter(n -> n.endsWith("folder-orphan"))
                        .toList());
        assertEquals(2, found.size(), found.toString());
    }

    @Test
    void recentFingerprintUseMeansInUseEvenWithoutStaticReference() throws Exception {
        UsernamePasswordCredentialsImpl c = add(j.jenkins, "runtime-only");
        FreeStyleProject p = j.createFreeStyleProject("p");
        Run<?, ?> run = j.buildAndAssertSuccess(p);
        CredentialsProvider.track(run, c);
        assertTrue(find(t.scan(collector, 10), "credential.unused", root("runtime-only"))
                .isEmpty());
    }

    @Test
    void oldFingerprintUseRaisesConfidenceToHigh() throws Exception {
        UsernamePasswordCredentialsImpl c = add(j.jenkins, "stale");
        Run<?, ?> run = j.buildAndAssertSuccess(j.createFreeStyleProject("p"));
        CredentialsProvider.track(run, c);
        Finding f =
                find(t.scan(collector, 400), "credential.unused", root("stale")).orElseThrow();
        assertEquals(Confidence.HIGH, f.confidence()); // static 45 + old last use 30
        assertEquals(Severity.HIGH, f.severity());
        assertTrue(f.evidence().stream().anyMatch(e -> e.text().contains("last use")));
    }

    @Test
    void disabledTrackingIsReportedAndGivesNoFingerprintWeight() throws Exception {
        System.setProperty("CredentialsProvider.fingerprintEnabled", "false");
        add(j.jenkins, "untracked");
        Finding f = find(t.scan(collector, 0), "credential.unused", root("untracked"))
                .orElseThrow();
        assertEquals(Confidence.MEDIUM, f.confidence());
        assertTrue(f.evidence().stream().anyMatch(e -> e.text().contains("tracking is disabled")));
    }

    @Test
    void latestRunScriptCountsEvenIfJobWasEditedSince() throws Exception {
        add(j.jenkins, "ghost");
        WorkflowJob p = pipeline("p", "def cfg = [credentialsId: 'ghost']\necho 'hi'");
        j.buildAndAssertSuccess(p);
        p.setDefinition(new CpsFlowDefinition("echo 'no reference any more'", true));
        assertTrue(
                find(t.scan(collector, 0), "credential.unused", root("ghost")).isEmpty(),
                "the last run's Jenkinsfile still referenced it");
    }

    @Test
    void jcascFileReferencesCountAndRemoteSourcesLowerConfidence() throws Exception {
        add(j.jenkins, "casc-used");
        add(j.jenkins, "other");
        Path yaml = dir.resolve("jenkins.yaml");
        Files.writeString(yaml, "unclassified:\n  something:\n    credentialsId: \"casc-used\"\n");
        System.setProperty("casc.jenkins.config", yaml + ",https://example.invalid/more.yaml");
        List<Finding> found = t.scan(collector, 0);
        assertTrue(find(found, "credential.unused", root("casc-used")).isEmpty());
        Finding other = find(found, "credential.unused", root("other")).orElseThrow();
        assertEquals(Confidence.LOW, other.confidence(), "remote JCasC source could not be read");
        assertTrue(other.evidence().stream().anyMatch(e -> e.text().contains("remote")));
    }

    @Test
    void cutShortScanEmitsNothingBecauseUnusedCannotBeProven() throws Exception {
        add(j.jenkins, "orphan");
        for (int i = 0; i < 20; i++) {
            j.createFreeStyleProject("job" + i);
        }
        ScanSettings tiny = new ScanSettings(Set.of(), Duration.ofMinutes(1), 10, 100);
        List<Finding> found = t.scan(collector, 0, tiny);
        assertTrue(found.isEmpty());
        assertEquals(CollectorOutcome.Status.PARTIAL, t.last.outcomes().get(0).status());
    }

    @Test
    void secretsAndDescriptionsNeverAppearInAnyOutput() throws Exception {
        add(j.jenkins, "orphan");
        add(j.jenkins, "used");
        pipeline("p", "git credentialsId: 'used'");
        t.scan(collector, 0);
        String everything = t.store.all().toString()
                + Files.readString(dir.resolve("findings.json"))
                + t.last
                + t.store.all().stream()
                        .map(io.jenkins.plugins.hygieneauditor.store.FindingJson::toJson)
                        .toList();
        for (String forbidden : List.of(PASSWORD, "Hunter2", USERNAME, "descsecret99", "prod deploy")) {
            assertFalse(everything.contains(forbidden), "leaked: " + forbidden);
        }
    }

    @Test
    void findingsResolveWhenCredentialIsDeletedOrBecomesReferenced() throws Exception {
        add(j.jenkins, "temp");
        assertTrue(find(t.scan(collector, 0), "credential.unused", root("temp")).isPresent());
        pipeline("p", "git credentialsId: 'temp'");
        assertTrue(find(t.scan(collector, 0), "credential.unused", root("temp")).isEmpty());
    }
}
