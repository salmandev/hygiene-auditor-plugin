package io.jenkins.plugins.hygieneauditor.collector.credentials;

import hudson.model.Job;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

/**
 * Recovers the pipeline source of a job's most recent run (its Jenkinsfile for multibranch / SCM pipelines, and any
 * scripts it loaded). Isolated here because it needs the optional workflow-cps plugin. Nothing is executed.
 */
final class PipelineScripts {
    private PipelineScripts() {}

    static List<String> lastRunScripts(Job<?, ?> job) {
        if (!(job instanceof WorkflowJob wj)) {
            return List.of();
        }
        WorkflowRun run = wj.getLastBuild();
        if (run == null) {
            return List.of();
        }
        FlowExecution fe = run.getExecution();
        if (!(fe instanceof CpsFlowExecution cps)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        String main = cps.getScript();
        if (main != null) {
            out.add(main);
        }
        Map<String, String> loaded = cps.getLoadedScripts();
        if (loaded != null) {
            out.addAll(loaded.values());
        }
        return out;
    }
}
