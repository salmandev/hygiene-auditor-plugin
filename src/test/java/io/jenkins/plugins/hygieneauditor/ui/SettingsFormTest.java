package io.jenkins.plugins.hygieneauditor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration;
import io.jenkins.plugins.hygieneauditor.policy.IgnoreRule;
import java.util.List;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SettingsFormTest {

    @Test
    void savingTheUntouchedFormChangesNothing(JenkinsRule j) throws Exception {
        HygieneAuditorConfiguration c = HygieneAuditorConfiguration.get();
        c.setScanCron("H 3 * * 0");
        c.setStaleJobDays(77);
        c.setOfflineAgentDays(9);
        c.setMaxFindings(1234);
        c.setDisabledCollectors(List.of("agents"));
        IgnoreRule r = new IgnoreRule("CREDENTIALS");
        r.setSubject("^prod-.*");
        r.setReason("platform team");
        IgnoreRule r2 = new IgnoreRule(null);
        r2.setFolder("sandbox");
        c.setIgnoreRules(List.of(r, r2));

        j.configRoundtrip();

        HygieneAuditorConfiguration after = HygieneAuditorConfiguration.get();
        assertEquals("H 3 * * 0", after.getScanCron());
        assertEquals(77, after.getStaleJobDays());
        assertEquals(9, after.getOfflineAgentDays());
        assertEquals(1234, after.getMaxFindings());
        assertEquals(List.of("agents"), after.getDisabledCollectors());
        assertEquals(2, after.getIgnoreRules().size());
        assertEquals("^prod-.*", after.getIgnoreRules().get(0).getSubject());
        assertEquals("CREDENTIALS", after.getIgnoreRules().get(0).getCategory());
        assertEquals("platform team", after.getIgnoreRules().get(0).getReason());
        assertEquals("sandbox", after.getIgnoreRules().get(1).getFolder());
    }

    @Test
    void savedExemptionsAreListedOnThePage(JenkinsRule j) throws Exception {
        IgnoreRule r = new IgnoreRule("CREDENTIALS");
        r.setSubject("^prod-.*");
        HygieneAuditorConfiguration.get().setIgnoreRules(List.of(r));
        HtmlPage page = j.createWebClient().goTo("manage/configure");
        assertTrue(
                page.getByXPath("//input[@name='_.subject' and @value='^prod-.*']")
                                .size()
                        == 1,
                "the saved rule is shown in the form");
    }

    @Test
    void editingTheFormAppliesTheChanges(JenkinsRule j) throws Exception {
        HtmlPage page = j.createWebClient().goTo("manage/configure");
        HtmlForm form = page.getFormByName("config");
        form.getInputByName("_.staleJobDays").setValue("123");
        form.getTextAreaByName("_.scanCron").setText("H 4 * * 1");
        HtmlCheckBoxInput creds = form.getInputByName("collector_credentials");
        assertTrue(creds.isChecked(), "enabled by default");
        creds.setChecked(false);
        j.submit(form);

        HygieneAuditorConfiguration c = HygieneAuditorConfiguration.get();
        assertEquals(123, c.getStaleJobDays());
        assertEquals("H 4 * * 1", c.getScanCron());
        assertEquals(List.of("credentials"), c.getDisabledCollectors());
        assertFalse(c.isCollectorEnabled("credentials"));
        assertTrue(c.isCollectorEnabled("jobs"));
    }
}
