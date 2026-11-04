package io.jenkins.plugins.hygieneauditor.collector.plugins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.PluginWrapper;
import hudson.model.Job;
import java.util.Set;
import java.util.TreeSet;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;

/**
 * Which plugins provided the steps that a pipeline job's latest run executed, recovered from the flow graph.
 * Isolated because it needs the optional Pipeline plugins. Steps defined by core map to no plugin and are ignored.
 */
final class PipelineStepPlugins {
    /** A run's graph is bounded so one enormous build cannot dominate a scan. */
    static final int MAX_NODES = 5_000;

    private PipelineStepPlugins() {}

    /** @return short names of plugins whose steps ran; empty for non-pipeline jobs or jobs that never ran */
    @NonNull
    static Set<String> of(Job<?, ?> job) {
        Set<String> out = new TreeSet<>();
        if (!(job instanceof WorkflowJob wj)) {
            return out;
        }
        WorkflowRun run = wj.getLastBuild();
        FlowExecution fe = run == null ? null : run.getExecution();
        if (fe == null) {
            return out;
        }
        int seen = 0;
        for (FlowNode n : new DepthFirstScanner().allNodes(fe)) {
            if (++seen > MAX_NODES) {
                break;
            }
            StepDescriptor d = n instanceof StepAtomNode a
                    ? a.getDescriptor()
                    : n instanceof StepStartNode s ? s.getDescriptor() : null;
            if (d != null) {
                PluginWrapper pw = Jenkins.get().getPluginManager().whichPlugin(d.getClass());
                if (pw != null) {
                    out.add(pw.getShortName());
                }
            }
        }
        return out;
    }
}
