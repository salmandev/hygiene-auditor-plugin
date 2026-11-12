package io.jenkins.plugins.hygieneauditor.step;

import java.util.List;
import org.jenkinsci.plugins.workflow.cps.SnippetizerTester;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The Pipeline Syntax snippet generator can configure the step: form data becomes the step, and the step prints back. */
@WithJenkins
class HygieneScanStepSnippetizerTest {

    @Test
    void defaultsRoundTrip(JenkinsRule j) throws Exception {
        new SnippetizerTester(j).assertRoundTrip(new HygieneScanStep(), "hygieneScan()");
    }

    @Test
    void parametersRoundTrip(JenkinsRule j) throws Exception {
        HygieneScanStep step = new HygieneScanStep();
        step.setCategories(List.of("JOBS", "CREDENTIALS"));
        step.setFailOnHigh(true);
        new SnippetizerTester(j)
                .assertRoundTrip(step, "hygieneScan categories: ['JOBS', 'CREDENTIALS'], failOnHigh: true");
    }
}
