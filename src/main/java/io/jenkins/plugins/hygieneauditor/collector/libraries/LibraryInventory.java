package io.jenkins.plugins.hygieneauditor.collector.libraries;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Job;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries;
import org.jenkinsci.plugins.workflow.libs.LibrariesAction;
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration;
import org.jenkinsci.plugins.workflow.libs.LibraryRecord;

/**
 * The only class that talks to the Pipeline library and Pipeline job APIs (so the collector loads without them).
 * See docs/LIBRARIES-FEASIBILITY.md for why these sources, and which others do not exist.
 */
final class LibraryInventory {
    private static final Pattern SAFE_DIR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

    /** A library that one run loaded, as recorded by Jenkins. */
    record Loaded(String name, String version, String dirName, Set<String> variables) {}

    /** What one pipeline job's most informative recent run tells us. */
    record RunFacts(
            String jobFullName,
            long timeMillis,
            @CheckForNull Path buildDir,
            List<Loaded> loaded,
            List<String> scripts) {}

    private LibraryInventory() {}

    @NonNull
    static List<ConfiguredLibrary> global() {
        List<ConfiguredLibrary> out = new ArrayList<>();
        for (LibraryConfiguration c : GlobalLibraries.get().getLibraries()) {
            out.add(new ConfiguredLibrary("", c.getName(), c.getDefaultVersion(), c.isImplicit()));
        }
        return out;
    }

    /** @return null if the job is not a pipeline job; {@code hasRun} false means it never ran */
    @CheckForNull
    static RunFacts examine(Job<?, ?> job) {
        if (!(job instanceof WorkflowJob wj)) {
            return null;
        }
        WorkflowRun run = wj.getLastBuild();
        if (run == null) {
            return new RunFacts(job.getFullName(), 0, null, List.of(), List.of());
        }
        if (run.getAction(LibrariesAction.class) == null) {
            WorkflowRun ok = wj.getLastSuccessfulBuild(); // the latest run may have failed before loading libraries
            if (ok != null && ok.getAction(LibrariesAction.class) != null) {
                run = ok;
            }
        }
        List<Loaded> loaded = new ArrayList<>();
        LibrariesAction la = run.getAction(LibrariesAction.class);
        if (la != null) {
            for (LibraryRecord r : la.getLibraries()) {
                loaded.add(new Loaded(
                        r.getName(),
                        String.valueOf(r.getVersion()),
                        r.getDirectoryName(),
                        new LinkedHashSet<>(r.getVariables())));
            }
        }
        List<String> scripts = new ArrayList<>();
        FlowExecution fe = run.getExecution();
        if (fe instanceof CpsFlowExecution cps) {
            if (cps.getScript() != null) {
                scripts.add(cps.getScript());
            }
            Map<String, String> more = cps.getLoadedScripts();
            if (more != null) {
                scripts.addAll(more.values());
            }
        }
        return new RunFacts(
                job.getFullName(), run.getTimeInMillis(), run.getRootDir().toPath(), loaded, scripts);
    }

    /** The checkout directory of a loaded library inside a build, or null if the recorded name looks unsafe. */
    @CheckForNull
    static Path checkoutDir(Path buildDir, String dirName) {
        if (dirName == null || !SAFE_DIR.matcher(dirName).matches()) {
            return null; // recorded data is read from disk: never let it steer the path
        }
        return buildDir.resolve("libs").resolve(dirName);
    }
}
