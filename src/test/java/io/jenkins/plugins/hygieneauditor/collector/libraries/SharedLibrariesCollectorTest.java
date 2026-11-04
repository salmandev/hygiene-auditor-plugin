package io.jenkins.plugins.hygieneauditor.collector.libraries;

import static io.jenkins.plugins.hygieneauditor.collector.CollectorTestSupport.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorPermissions;
import io.jenkins.plugins.hygieneauditor.collector.CollectorTestSupport;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.scan.CollectorOutcome;
import io.jenkins.plugins.hygieneauditor.scan.ScanSettings;
import io.jenkins.plugins.hygieneauditor.security.FindingAccess;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.libs.FolderLibraries;
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Real git libraries, real pipeline runs, the real collector. */
@WithJenkins
class SharedLibrariesCollectorTest {
    @TempDir
    Path dir;

    JenkinsRule j;
    CollectorTestSupport t;
    final SharedLibrariesCollector collector = new SharedLibrariesCollector();

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        LibraryFixture.allowLocalCheckout();
        t = new CollectorTestSupport(dir.resolve("store"));
    }

    private static Map<String, String> files(String... pathAndContent) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pathAndContent.length; i += 2) {
            m.put(pathAndContent[i], pathAndContent[i + 1]);
        }
        return m;
    }

    private Path repo(String name, String... pathAndContent) throws Exception {
        return LibraryFixture.repo(dir.resolve(name), files(pathAndContent));
    }

    private WorkflowJob run(String jobName, String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, jobName);
        p.setDefinition(LibraryFixture.script(script));
        j.buildAndAssertSuccess(p);
        return p;
    }

    private List<Finding> scan() throws Exception {
        return t.scan(collector, 0);
    }

    private static boolean has(List<Finding> fs, String type, String subject) {
        return find(fs, type, subject).isPresent();
    }

    private static final String ACME_VARS = "def call(String what) { helperVar(); echo \"deploying ${what}\" }\n";

    private void acme() throws Exception {
        Path acme = repo(
                "acme",
                "vars/deploy.groovy",
                ACME_VARS,
                "vars/helperVar.groovy",
                "def call() { echo 'helper' }\n",
                "vars/neverCalled.groovy",
                "def call() { echo 'never' }\n",
                "vars/selfRef.groovy",
                "def call() { selfRef() }\n",
                "src/org/acme/Util.groovy",
                "package org.acme\nclass Util extends Base implements Serializable { String hi() { 'hi' } }\n",
                "src/org/acme/Base.groovy",
                "package org.acme\nclass Base implements Serializable { }\n",
                "src/org/acme/Dead.groovy",
                "package org.acme\nclass Dead implements Serializable { }\n");
        Path dormant = repo("dormant", "vars/lonely.groovy", "def call() { echo 'x' }\n");
        GlobalLibraries.get()
                .setLibraries(List.of(LibraryFixture.config("acme", acme), LibraryFixture.config("dormant", dormant)));
    }

    // ------------------------------------------------------------------

    @Test
    void reportsExactlyWhatIsUnusedAndNothingThatIsUsed() throws Exception {
        acme();
        run("uses-lib", "@Library('acme@main') _\nimport org.acme.Util\ndeploy('prod')\necho new Util().hi()\n");
        List<Finding> found = scan();

        assertEquals(CollectorOutcome.Status.COMPLETE, t.last.outcomes().get(0).status());
        assertTrue(has(found, "library.unreferenced", ":dormant"), found.toString());
        assertTrue(has(found, "library.unused_variable", ":acme:neverCalled"));
        assertTrue(has(found, "library.unused_variable", ":acme:selfRef"), "calling only itself is not a use");
        assertTrue(has(found, "library.unused_class", ":acme:org.acme.Dead"));

        assertFalse(has(found, "library.unreferenced", ":acme"), "it was loaded");
        assertFalse(has(found, "library.unused_variable", ":acme:deploy"), "called by the job");
        assertFalse(has(found, "library.unused_variable", ":acme:helperVar"), "called by another library file");
        assertFalse(has(found, "library.unused_class", ":acme:org.acme.Util"), "imported by the job");
        assertFalse(
                has(found, "library.unused_class", ":acme:org.acme.Base"),
                "extended by a same-package class, no import");
        assertEquals(4, found.size(), found.toString());
    }

    @Test
    void evidenceStatesWhatWasSeenAndWhatWasNotChecked() throws Exception {
        acme();
        run("uses-lib", "@Library('acme@main') _\ndeploy('prod')\n");
        Finding f = find(scan(), "library.unused_variable", ":acme:neverCalled").orElseThrow();
        assertEquals(Confidence.MEDIUM, f.confidence()); // 50: not broad coverage with a single run
        String text = f.evidence().toString();
        assertTrue(text.contains("mentions 'neverCalled'"), text);
        assertTrue(text.contains("versions loaded by recent runs: main"), text);
        assertTrue(text.contains("Not checked: older runs"), text);
        Finding lib = find(scan(), "library.unreferenced", ":dormant").orElseThrow();
        assertEquals(Confidence.MEDIUM, lib.confidence()); // 35 + 25
        assertTrue(lib.evidence().toString().contains("Configured version: main"));
    }

    @Test
    void coverageOfManyJobsRaisesConfidence() throws Exception {
        acme();
        for (int i = 0; i < 10; i++) {
            run("job" + i, "@Library('acme@main') _\ndeploy('x" + i + "')\n");
        }
        Finding v = find(scan(), "library.unused_variable", ":acme:neverCalled").orElseThrow();
        // 50 + 15 broad coverage = 65: static evidence over latest runs alone never reaches HIGH for a variable
        assertEquals(Confidence.MEDIUM, v.confidence());
        assertTrue(
                v.evidence().toString().contains("Broad coverage"), v.evidence().toString());
        Finding lib = find(scan(), "library.unreferenced", ":dormant").orElseThrow();
        assertEquals(Confidence.MEDIUM, lib.confidence()); // 35 + 25; the coverage bonus for this verdict needs 20 jobs
    }

    @Test
    void nothingIsClaimedWhenNoPipelineHasRun() throws Exception {
        acme();
        WorkflowJob p = j.createProject(WorkflowJob.class, "never-ran");
        p.setDefinition(LibraryFixture.script("@Library('acme@main') _\ndeploy('x')\n"));
        assertTrue(scan().isEmpty(), "no run => no basis for any verdict");
    }

    @Test
    void jobsThatNeverRanLowerConfidenceBecauseTheyMightUseTheLibrary() throws Exception {
        acme();
        run("uses-lib", "@Library('acme@main') _\ndeploy('prod')\n");
        WorkflowJob p = j.createProject(WorkflowJob.class, "never-ran");
        p.setDefinition(LibraryFixture.script("echo 'x'"));
        Finding lib = find(scan(), "library.unreferenced", ":dormant").orElseThrow();
        assertEquals(Confidence.LOW, lib.confidence());
        assertTrue(lib.evidence().toString().contains("never run"));
    }

    @Test
    void dynamicLibraryRequestsAndInvocationsLowerConfidence() throws Exception {
        acme();
        run(
                "uses-lib",
                "@Library('acme@main') _\ndeploy('prod')\nif (false) { library(someLib); evaluate(someExpression) }\n");
        List<Finding> found = scan();
        Finding lib = find(found, "library.unreferenced", ":dormant").orElseThrow();
        assertEquals(Confidence.LOW, lib.confidence());
        assertTrue(lib.evidence().toString().contains("cannot be resolved"));
        Finding v = find(found, "library.unused_variable", ":acme:neverCalled").orElseThrow();
        assertEquals(Confidence.LOW, v.confidence());
        assertTrue(v.evidence().toString().contains("dynamic invocation"));
    }

    @Test
    void aLibraryNamedOnlyInSourceCountsAsReferencedEvenIfNotLoadedByTheLatestRun() throws Exception {
        acme();
        run("uses-lib", "@Library('acme@main') _\ndeploy('prod')\nif (false) { library 'dormant@main' }\n");
        assertFalse(has(scan(), "library.unreferenced", ":dormant"));
    }

    @Test
    void classesAreReachedThroughFullyQualifiedNamesAndWildcardImports() throws Exception {
        Path lib = repo(
                "lib",
                "vars/v.groovy",
                "def call() { echo 'v' }\n",
                "src/org/lib/ByFqcn.groovy",
                "package org.lib\nclass ByFqcn implements Serializable { }\n",
                "src/org/lib/ByWildcard.groovy",
                "package org.lib\nclass ByWildcard implements Serializable { }\n",
                "src/org/lib/Unreached.groovy",
                "package org.lib\nclass Unreached implements Serializable { }\n",
                "src/Standalone.groovy",
                "class Standalone implements Serializable { }\n");
        GlobalLibraries.get().setLibraries(List.of(LibraryFixture.config("lib", lib)));
        run(
                "p",
                "@Library('lib@main') _\nimport org.lib.*\nv()\necho new org.lib.ByFqcn().toString()\necho new ByWildcard().toString()\necho new Standalone().toString()\n");
        List<Finding> found = scan();
        assertFalse(has(found, "library.unused_class", ":lib:org.lib.ByFqcn"));
        assertFalse(has(found, "library.unused_class", ":lib:org.lib.ByWildcard"));
        assertFalse(has(found, "library.unused_class", ":lib:Standalone"), "default package matched by simple name");
        Finding unreached =
                find(found, "library.unused_class", ":lib:org.lib.Unreached").orElseThrow();
        assertEquals(Confidence.LOW, unreached.confidence(), "a wildcard import could reach it: caveat");
        assertTrue(unreached.evidence().toString().contains("org.lib.*"));
    }

    @Test
    void folderLibrariesAreScopedToTheirSubtree() throws Exception {
        acme();
        Path teamLib = repo(
                "teamlib",
                "vars/teamVar.groovy",
                "def call() { echo 't' }\n",
                "vars/spare.groovy",
                "def call() { echo 's' }\n");
        Folder team = j.jenkins.createProject(Folder.class, "team");
        team.getProperties().add(new FolderLibraries(List.of(LibraryFixture.config("teamlib", teamLib))));
        WorkflowJob inside = team.createProject(WorkflowJob.class, "p");
        inside.setDefinition(LibraryFixture.script("@Library('teamlib@main') _\nteamVar()\n"));
        j.buildAndAssertSuccess(inside);
        run("outside", "@Library('acme@main') _\ndeploy('x')\n"); // cannot see teamlib

        List<Finding> found = scan();
        assertTrue(has(found, "library.unused_variable", "team:teamlib:spare"), found.toString());
        assertFalse(has(found, "library.unused_variable", "team:teamlib:teamVar"));
        assertFalse(has(found, "library.unreferenced", "team:teamlib"));
        Finding f = find(found, "library.unused_variable", "team:teamlib:spare").orElseThrow();
        assertEquals("library_var", f.subject().kind());
        assertEquals(
                1,
                f.evidence().stream()
                        .filter(e -> e.text().contains("1 inspected job(s)"))
                        .count(),
                "only the one job inside the folder counts as coverage");
    }

    @Test
    void unusedFolderLibraryIsReportedWithItsFolderAsOwner() throws Exception {
        acme();
        Path teamLib = repo("teamlib", "vars/teamVar.groovy", "def call() { echo 't' }\n");
        Folder team = j.jenkins.createProject(Folder.class, "team");
        team.getProperties().add(new FolderLibraries(List.of(LibraryFixture.config("teamlib", teamLib))));
        WorkflowJob inside = team.createProject(WorkflowJob.class, "p");
        inside.setDefinition(LibraryFixture.script("echo 'does not use the library'"));
        j.buildAndAssertSuccess(inside);
        assertTrue(has(scan(), "library.unreferenced", "team:teamlib"));
    }

    @Test
    void libraryFindingsAreForAdministratorsOnly() throws Exception {
        acme();
        run("uses-lib", "@Library('acme@main') _\ndeploy('prod')\n");
        scan();
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ, hudson.model.Item.READ, HygieneAuditorPermissions.VIEW)
                .everywhere()
                .to("viewer"));
        assertFalse(t.store.all().isEmpty(), "there are library findings to hide");
        try (ACLContext ignored = ACL.as2(User.getById("viewer", true).impersonate2())) {
            assertTrue(
                    FindingAccess.filter(t.store.all()).isEmpty(),
                    "VIEW + Item.READ is not enough for library findings: they need Administer");
        }
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertFalse(FindingAccess.filter(t.store.all()).isEmpty());
        }
    }

    @Test
    void aCutShortScanEmitsNothing() throws Exception {
        acme();
        for (int i = 0; i < 8; i++) {
            run("job" + i, "@Library('acme@main') _\ndeploy('x')\n");
        }
        ScanSettings tiny = new ScanSettings(Set.of(), Duration.ofMinutes(1), 5, 100);
        assertTrue(t.scan(collector, 0, tiny).isEmpty());
        assertEquals(CollectorOutcome.Status.PARTIAL, t.last.outcomes().get(0).status());
    }

    @Test
    void libraryCodeIsNeverExecutedByTheScan() throws Exception {
        Path lib = repo(
                "evil",
                "vars/ok.groovy",
                "def call() { echo 'ok' }\n",
                "vars/evil.groovy",
                "def call() { System.setProperty('hygiene.pwned', 'yes') }\n",
                "src/org/evil/Boom.groovy",
                "package org.evil\nclass Boom { static { System.setProperty('hygiene.pwned', 'static') } }\n");
        GlobalLibraries.get().setLibraries(List.of(LibraryFixture.config("evil", lib)));
        run("p", "@Library('evil@main') _\nok()\n");
        System.clearProperty("hygiene.pwned");
        List<Finding> found = scan();
        assertNull(System.getProperty("hygiene.pwned"), "scanned library code is text, never run");
        assertTrue(has(found, "library.unused_variable", ":evil:evil"));
    }
}
