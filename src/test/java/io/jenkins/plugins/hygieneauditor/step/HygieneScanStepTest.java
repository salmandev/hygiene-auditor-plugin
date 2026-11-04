package io.jenkins.plugins.hygieneauditor.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import io.jenkins.plugins.hygieneauditor.scan.TestStubs;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class HygieneScanStepTest {

    JenkinsRule j;

    @TestExtension
    public static class Stub extends TestStubs.StubCollector {}

    @org.junit.jupiter.api.BeforeEach
    void isolateStub(JenkinsRule rule) {
        this.j = rule;
        io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration.get()
                .setDisabledCollectors(java.util.List.of("plugins"));
    }

    @AfterEach
    void reset() {
        TestStubs.BODY.set(ctx -> {});
    }

    private WorkflowRun run(JenkinsRule j, String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p.scheduleBuild2(0).get(120, TimeUnit.SECONDS);
    }

    @Test
    void returnsCountsAndLogsSummary() throws Exception {
        TestStubs.BODY.set(ctx -> ctx.emit(TestStubs.highPluginFinding()));
        WorkflowRun b = run(
                j,
                "def r = hygieneScan(categories: ['plugins'])\n"
                        + "echo \"open=${r.open} high=${r.high} created=${r.created} state=${r.state}\"");
        j.assertBuildStatusSuccess(b);
        j.assertLogContains("open=1 high=1 created=1 state=COMPLETED", b);
        j.assertLogContains("Hygiene scan COMPLETED", b);
    }

    @Test
    void failOnHighFailsTheBuild() throws Exception {
        TestStubs.BODY.set(ctx -> ctx.emit(TestStubs.highPluginFinding()));
        WorkflowRun b = run(j, "hygieneScan(categories: ['PLUGINS'], failOnHigh: true)");
        assertEquals(Result.FAILURE, b.getResult());
        j.assertLogContains("1 HIGH-severity hygiene finding(s)", b);
    }

    @Test
    void failOnHighPassesWhenNothingIsHigh() throws Exception {
        WorkflowRun b = run(j, "hygieneScan(categories: ['PLUGINS'], failOnHigh: true)");
        j.assertBuildStatusSuccess(b);
    }

    @Test
    void unknownCategoryIsAClearError() throws Exception {
        WorkflowRun b = run(j, "hygieneScan(categories: ['BOGUS'])");
        assertEquals(Result.FAILURE, b.getResult());
        j.assertLogContains("Unknown category 'BOGUS'", b);
        j.assertLogContains("PLUGINS", b);
    }

    @Test
    void scanStartedByPipelineIsRecordedWithPipelineTrigger() throws Exception {
        j.assertBuildStatusSuccess(run(j, "hygieneScan(categories: ['PLUGINS'])"));
        assertEquals(
                "pipeline",
                io.jenkins.plugins.hygieneauditor.scan.ScanService.get()
                        .lastScan()
                        .trigger());
        assertTrue(io.jenkins.plugins.hygieneauditor.scan.ScanService.get()
                .status()
                .isEmpty());
    }
}
