package io.jenkins.plugins.hygieneauditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.casc.misc.junit.jupiter.AbstractRoundTripTest;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import java.util.List;
import org.jvnet.hudson.test.JenkinsRule;

/** Import -> export -> compare, plus validation of bad input. */
@WithJenkinsConfiguredWithCode
class ConfigurationAsCodeTest extends AbstractRoundTripTest {

    @Override
    protected String configResource() {
        return "casc-full.yml";
    }

    @Override
    protected void assertConfiguredAsExpected(JenkinsRule j, String configContent) {
        HygieneAuditorConfiguration c = HygieneAuditorConfiguration.get();
        assertEquals("H 3 * * 0", c.getScanCron());
        assertEquals(List.of("agents"), c.getDisabledCollectors());
        assertFalse(c.isCollectorEnabled("agents"));
        assertTrue(c.isCollectorEnabled("jobs"));
        assertEquals(200, c.getStaleJobDays());
        assertEquals(10, c.getNeverBuiltGraceDays());
        assertEquals(90, c.getDisabledJobDays());
        assertEquals(14, c.getOfflineAgentDays());
        assertEquals(120, c.getCredentialStaleUseDays());
        assertEquals(20000, c.getMaxFindings());
        assertEquals(7, c.getResolvedRetentionDays());
        assertEquals(3, c.getIgnoreRules().size());
        assertEquals("^prod-.*", c.getIgnoreRules().get(0).getSubject());
        assertEquals("CREDENTIALS", c.getIgnoreRules().get(0).getCategory());
        assertEquals("sandbox", c.getIgnoreRules().get(1).getFolder());
        assertEquals("job.*", c.getIgnoreRules().get(2).getType());
        // derived views used by the engine
        assertEquals(200, c.jobsSettings().staleDays());
        assertEquals(14, c.agentsSettings().offlineDays());
        assertEquals(20000, c.retentionPolicy().maxFindings());
    }

    @Override
    protected String stringInLogExpected() {
        return "hygieneAuditor";
    }
}
