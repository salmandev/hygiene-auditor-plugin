package io.jenkins.plugins.hygieneauditor.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class FindingAccessTest {
    JenkinsRule j;
    Folder a;
    Folder b;

    private static Finding finding(String type, Category cat, String kind, String name) {
        return Finding.observed(
                new FindingCandidate(
                        type,
                        cat,
                        Subject.of(kind, name, null),
                        Severity.MEDIUM,
                        Confidence.MEDIUM,
                        List.of(new Evidence("t", "e", 1)),
                        "x"),
                "c",
                Instant.now());
    }

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        a = j.jenkins.createProject(Folder.class, "a");
        b = j.jenkins.createProject(Folder.class, "b");
        a.createProject(FreeStyleProject.class, "j1");
        b.createProject(FreeStyleProject.class, "j1");
        List<Finding> fs = List.of(
                finding("job.stale", Category.JOBS, "job", "a/j1"),
                finding("job.stale", Category.JOBS, "job", "b/j1"),
                finding("job.stale", Category.JOBS, "job", "gone/old"),
                finding("credential.unused", Category.CREDENTIALS, "credential", "a:_:cred-a"),
                finding("credential.unused", Category.CREDENTIALS, "credential", "b:_:cred-b"),
                finding("credential.unused", Category.CREDENTIALS, "credential", ":_:cred-root"),
                finding("agent.offline", Category.AGENTS, "agent", "node1"));
        FindingStoreHolder.get().store().recordScan(fs, Set.of(), Instant.now());

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                // alice: auditor for folder a only
                .grant(Jenkins.READ)
                .everywhere()
                .to("alice", "bob", "carol")
                .grant(Item.READ, HygieneAuditorPermissions.VIEW, CredentialsProvider.VIEW)
                .onFolders(a)
                .to("alice")
                // bob: global VIEW and read, but no credentials permission
                .grant(HygieneAuditorPermissions.VIEW, Item.READ)
                .everywhere()
                .to("bob")
                // carol: can read jobs but has no Hygiene Auditor permission at all
                .grant(Item.READ, CredentialsProvider.VIEW)
                .everywhere()
                .to("carol")
                // dave: VIEW on folder a but cannot read the items there
                .grant(Jenkins.READ)
                .everywhere()
                .to("dave")
                .grant(HygieneAuditorPermissions.VIEW)
                .onFolders(a)
                .to("dave"));
    }

    private Set<String> seenBy(String user) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            Set<String> out = new TreeSet<>();
            for (Finding f :
                    FindingAccess.filter(FindingStoreHolder.get().store().all())) {
                out.add(f.subject().fullName());
            }
            return out;
        }
    }

    @Test
    void permissionsImplyEachOtherInOneDirection() throws Exception {
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(HygieneAuditorPermissions.CONFIGURE)
                .everywhere()
                .to("configurer")
                .grant(HygieneAuditorPermissions.SCAN)
                .everywhere()
                .to("scanner")
                .grant(HygieneAuditorPermissions.VIEW)
                .everywhere()
                .to("viewer")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("root"));
        record Case(String user, boolean view, boolean scan, boolean configure) {}
        for (Case c : List.of(
                new Case("viewer", true, false, false),
                new Case("scanner", true, true, false),
                new Case("configurer", true, true, true),
                new Case("root", true, true, true))) {
            try (ACLContext ignored = ACL.as2(User.getById(c.user(), true).impersonate2())) {
                Jenkins jenkins = Jenkins.get();
                assertEquals(c.view(), jenkins.hasPermission(HygieneAuditorPermissions.VIEW), c.user() + " view");
                assertEquals(c.scan(), jenkins.hasPermission(HygieneAuditorPermissions.SCAN), c.user() + " scan");
                assertEquals(
                        c.configure(),
                        jenkins.hasPermission(HygieneAuditorPermissions.CONFIGURE),
                        c.user() + " configure");
            }
        }
    }

    @Test
    void adminSeesEverythingIncludingDeletedSubjectsAndAgents() throws Exception {
        assertEquals(7, seenBy("admin").size());
    }

    @Test
    void folderScopedUserSeesOnlyThatFoldersJobsAndCredentials() throws Exception {
        assertEquals(Set.of("a/j1", "a:_:cred-a"), seenBy("alice"));
    }

    @Test
    void globalViewerWithoutCredentialsPermissionSeesNoCredentialsAndNoGlobalCategories() throws Exception {
        assertEquals(Set.of("a/j1", "b/j1"), seenBy("bob"));
    }

    @Test
    void readAccessAloneIsNotEnough() throws Exception {
        assertEquals(Set.of(), seenBy("carol"));
    }

    @Test
    void viewWithoutItemReadSeesNothing() throws Exception {
        assertEquals(Set.of(), seenBy("dave"));
    }

    @Test
    void anonymousSeesNothing() throws Exception {
        try (ACLContext ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            assertTrue(
                    FindingAccess.filter(FindingStoreHolder.get().store().all()).isEmpty());
        }
    }

    @Test
    void resultOrderIsPreserved() throws Exception {
        List<String> order = new ArrayList<>();
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            for (Finding f :
                    FindingAccess.filter(FindingStoreHolder.get().store().all())) {
                order.add(f.subject().fullName());
            }
        }
        assertFalse(order.isEmpty());
        assertEquals(
                FindingStoreHolder.get().store().all().stream()
                        .map(f -> f.subject().fullName())
                        .toList(),
                order);
    }
}
