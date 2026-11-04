package io.jenkins.plugins.hygieneauditor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SubjectLinksTest {

    private static String path(Subject s) {
        var l = SubjectLinks.of(s);
        return l == null ? null : l.path();
    }

    @Test
    void pointsAtJenkinsOwnPagesAndNeverAtAnythingMissing(JenkinsRule r) throws Exception {
        Folder f = r.jenkins.createProject(Folder.class, "legacy");
        f.createProject(hudson.model.FreeStyleProject.class, "old job");
        r.createSlave("agent-1", null, null);

        assertEquals("job/legacy/job/old%20job/", path(Subject.of("job", "legacy/old job", null)));
        assertEquals("computer/agent-1/", path(Subject.of("agent", "agent-1", null)));
        assertEquals(
                "manage/credentials/store/system/domain/_/credential/ssh%20key/",
                path(Subject.of("credential", ":_:ssh key", null)));
        assertEquals(
                "job/legacy/credentials/store/folder/domain/_/credential/k/",
                path(Subject.of("credential", "legacy:_:k", null)));
        assertEquals("manage/configure", path(Subject.of("library", ":lib", null)));
        assertEquals("job/legacy/configure", path(Subject.of("library_var", "legacy:lib:foo", null)));
        assertEquals("manage/pluginManager/installed", path(Subject.of("plugin", "git", null)));

        assertNull(path(Subject.of("job", "deleted/job", null)));
        assertNull(path(Subject.of("agent", "gone", null)));
        assertNull(path(Subject.of("credential", "malformed", null)));
        assertNull(path(Subject.of("credential", "nofolder:_:k", null)));
        assertNull(path(Subject.of("something_else", "x", null)));
    }

    @Test
    void aViewerGetsNoLinkToPagesTheyCouldNotOpen(JenkinsRule r) throws Exception {
        Folder f = r.jenkins.createProject(Folder.class, "legacy");
        f.createProject(hudson.model.FreeStyleProject.class, "j");
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("viewer")
                .grant(hudson.model.Item.READ)
                .onFolders(f)
                .to("viewer"));

        try (ACLContext ignored = ACL.as2(User.getById("viewer", true).impersonate2())) {
            assertNotNull(path(Subject.of("job", "legacy/j", null)));
            assertNull(path(Subject.of("credential", "legacy:_:k", null)));
            assertNull(path(Subject.of("credential", ":_:k", null)));
            assertNull(path(Subject.of("library", ":lib", null)));
            assertNull(path(Subject.of("plugin", "git", null)));
            assertNull(path(Subject.of("agent", "agent-1", null)));
        }
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertNotNull(path(Subject.of("credential", ":_:k", null)));
            assertNotNull(path(Subject.of("plugin", "git", null)));
        }
    }
}
