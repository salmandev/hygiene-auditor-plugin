package io.jenkins.plugins.hygieneauditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The JCasC example in the documentation is real: if a key is renamed or removed, this fails. */
@WithJenkins
class DocumentedExampleTest {

    @Test
    void theDocumentedJcascExampleLoadsAndMeansWhatTheReadmeSays(JenkinsRule j) throws Exception {
        Path example = Path.of("docs", "examples", "jcasc.yaml").toAbsolutePath();
        assertTrue(Files.exists(example), "run tests from the project directory: " + example);
        // The example references an environment variable for the secret; resolve it for the test.
        Path resolved = Files.createTempFile("jcasc", ".yaml");
        Files.writeString(resolved, Files.readString(example).replace("${HYGIENE_WEBHOOK_SECRET}", "test-secret"));
        ConfigurationAsCode.get().configure(resolved.toString());

        HygieneAuditorConfiguration c = HygieneAuditorConfiguration.get();
        assertEquals("H 3 * * 0", c.getScanCron());
        assertEquals(List.of("agents"), c.getDisabledCollectors());
        assertEquals(365, c.getStaleJobDays());
        assertEquals(3, c.getIgnoreRules().size());
        assertEquals(Severity.HIGH, c.getNotifyMinSeverity());
        assertEquals("https://hooks.example.com/hygiene", c.getWebhookUrl());
        assertEquals("test-secret", c.getWebhookSecret().getPlainText());
        assertEquals("ops@example.com platform@example.com", c.getEmailRecipients());
    }
}
