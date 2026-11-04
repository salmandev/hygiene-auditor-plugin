package io.jenkins.plugins.hygieneauditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class HygieneAuditorLinkTest {

    @Test
    void linkIsRegisteredAndPageRenders(JenkinsRule j) throws Exception {
        HygieneAuditorLink link =
                Jenkins.get().getExtensionList(HygieneAuditorLink.class).get(0);
        assertNotNull(link);
        assertEquals("hygiene-auditor", link.getUrlName());
        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            assertEquals(200, wc.goTo("manage/hygiene-auditor").getWebResponse().getStatusCode());
        }
    }
}
