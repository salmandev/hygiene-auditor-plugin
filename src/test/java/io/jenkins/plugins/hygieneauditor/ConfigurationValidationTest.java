package io.jenkins.plugins.hygieneauditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.ConfiguratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ConfigurationValidationTest {
    @TempDir
    Path dir;

    private void apply(String body) throws Exception {
        Path f = dir.resolve("c.yml");
        Files.writeString(f, "unclassified:\n  hygieneAuditor:\n" + body);
        ConfigurationAsCode.get().configure(f.toString());
    }

    private String rejected(String body) throws Exception {
        ConfiguratorException e = assertThrows(ConfiguratorException.class, () -> apply(body));
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append(" | ");
        }
        return all.toString();
    }

    @Test
    void emptySectionKeepsSafeDefaults(JenkinsRule j) throws Exception {
        apply("    staleJobDays: 365\n");
        HygieneAuditorConfiguration c = HygieneAuditorConfiguration.get();
        assertEquals("", c.getScanCron());
        assertEquals(30, c.getNeverBuiltGraceDays());
        assertEquals(50_000, c.getMaxFindings());
        assertTrue(c.getIgnoreRules().isEmpty());
        assertTrue(c.isCollectorEnabled("credentials"));
    }

    @Test
    void invalidValuesAreRejectedWithAUsefulMessage(JenkinsRule j) throws Exception {
        assertTrue(rejected("    staleJobDays: 0\n").contains("staleJobDays must be at least 1"));
        assertTrue(rejected("    resolvedRetentionDays: -1\n").contains("must not be negative"));
        assertTrue(rejected("    scanCron: \"not a cron\"\n").toLowerCase().contains("cron"));
        assertTrue(rejected("    ignoreRules:\n    - subject: \"([unclosed\"\n").contains("Invalid subject regex"));
        assertTrue(rejected("    ignoreRules:\n    - category: \"NOPE\"\n").contains("Unknown category"));
        assertTrue(rejected("    ignoreRules:\n    - reason: \"matches everything\"\n")
                .contains("needs at least one of"));
    }

    @Test
    void cronAdviceIsAWarningNotAnError(JenkinsRule j) throws Exception {
        apply("    scanCron: \"* * * * *\"\n");
        assertEquals("* * * * *", HygieneAuditorConfiguration.get().getScanCron());
        assertTrue(HygieneAuditorConfiguration.cronWarning("* * * * *") != null, "still advised against");
        assertEquals(null, HygieneAuditorConfiguration.cronWarning("H 3 * * 0"));
        assertEquals(null, HygieneAuditorConfiguration.validateCron("H 3 * * 0\n# comment\n0 12 * * 5"));
    }

    @Test
    void aRejectedConfigurationLeavesTheOldOneInPlace(JenkinsRule j) throws Exception {
        apply("    staleJobDays: 111\n");
        rejected("    staleJobDays: 222\n    offlineAgentDays: 0\n");
        assertEquals(111, HygieneAuditorConfiguration.get().getStaleJobDays());
    }
}
