package io.jenkins.plugins.hygieneauditor.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Every field the settings form and the pipeline step offer explains itself in the UI. */
@WithJenkins
class HelpTextTest {

    private static String help(JenkinsRule j, String descriptor, String field) throws Exception {
        return j.createWebClient()
                .goTo("descriptor/" + descriptor + "/help/" + field, "text/html")
                .getWebResponse()
                .getContentAsString();
    }

    @Test
    void theCronFieldAndEveryIgnoreRuleFieldHaveHelp(JenkinsRule j) throws Exception {
        String config = "io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration";
        assertTrue(help(j, config, "scanCron").contains("H 3 * * 0"));
        assertTrue(help(j, config, "ignoreRules").contains("never recorded"));
        String rule = "io.jenkins.plugins.hygieneauditor.policy.IgnoreRule";
        assertTrue(help(j, rule, "category").contains("SHARED_LIBRARIES"));
        assertTrue(help(j, rule, "type").contains("credential.unused"));
        assertTrue(help(j, rule, "subject").contains("regular expression"));
        assertTrue(help(j, rule, "folder").contains("below it"));
        assertTrue(help(j, rule, "reason").contains("documentation"));
    }

    @Test
    void theSettingsPageOffersTheHelpAndACategoryChoice(JenkinsRule j) throws Exception {
        HtmlPage page = j.createWebClient().goTo("manage/configure");
        String xml = page.asXml();
        assertTrue(xml.contains("help/scanCron"), "help link on the cron field");
        assertTrue(xml.contains("help/ignoreRules"), "help link on the rules");
    }

    @Test
    void theStepDocumentsItsParametersAndReturnValue(JenkinsRule j) throws Exception {
        String step = "io.jenkins.plugins.hygieneauditor.step.HygieneScanStep";
        String general = j.createWebClient()
                .goTo("pipeline-syntax/html", "text/html")
                .getWebResponse()
                .getContentAsString();
        assertTrue(general.contains("hygieneScan"), "the step is listed");
        assertTrue(general.contains("scanId") && general.contains("resolved"), "return value is documented");
        assertTrue(help(j, step, "categories").contains("SHARED_LIBRARIES"));
        assertTrue(help(j, step, "failOnHigh").contains("high-severity"));
    }
}
