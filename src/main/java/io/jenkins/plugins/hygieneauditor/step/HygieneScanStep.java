package io.jenkins.plugins.hygieneauditor.step;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.TaskListener;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorPermissions;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Status;
import io.jenkins.plugins.hygieneauditor.rest.FindingService;
import io.jenkins.plugins.hygieneauditor.scan.ScanAlreadyRunningException;
import io.jenkins.plugins.hygieneauditor.scan.ScanRecord;
import io.jenkins.plugins.hygieneauditor.scan.ScanRequest;
import io.jenkins.plugins.hygieneauditor.scan.ScanService;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * {@code hygieneScan(categories: ['CREDENTIALS', 'JOBS'], failOnHigh: false)}: runs a scan, waits for it, and
 * returns counts. Needs the SCAN permission as the build's identity (SYSTEM by default).
 *
 * <p>Returns only aggregate numbers, filtered by what the build's identity may see. Findings themselves are not
 * returned to pipeline code. With {@code failOnHigh}, the step fails if any OPEN HIGH-severity finding exists in the
 * scanned categories.
 */
public class HygieneScanStep extends Step {
    private List<String> categories = new ArrayList<>();
    private boolean failOnHigh;

    @DataBoundConstructor
    public HygieneScanStep() {}

    public List<String> getCategories() {
        return List.copyOf(categories);
    }

    @DataBoundSetter
    public void setCategories(List<String> categories) {
        this.categories = categories == null ? new ArrayList<>() : new ArrayList<>(categories);
    }

    public boolean isFailOnHigh() {
        return failOnHigh;
    }

    @DataBoundSetter
    public void setFailOnHigh(boolean failOnHigh) {
        this.failOnHigh = failOnHigh;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, new ArrayList<>(categories), failOnHigh);
    }

    static Set<Category> parse(List<String> names) throws AbortException {
        Set<Category> out = EnumSet.noneOf(Category.class);
        for (String n : names) {
            try {
                out.add(Category.valueOf(n.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new AbortException("Unknown category '" + n + "'. Valid: " + List.of(Category.values()));
            }
        }
        return out;
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<Map<String, Object>> {
        private static final long serialVersionUID = 1L;
        private static final int MAX_WAIT_FOR_RUNNING_SCANS = 5;

        private final ArrayList<String> categories;
        private final boolean failOnHigh;

        Execution(StepContext context, ArrayList<String> categories, boolean failOnHigh) {
            super(context);
            this.categories = categories;
            this.failOnHigh = failOnHigh;
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            Jenkins.get().checkPermission(HygieneAuditorPermissions.SCAN);
            TaskListener listener = getContext().get(TaskListener.class);
            PrintStream log = listener.getLogger();
            Set<Category> cats = parse(categories);

            ScanService.Handle handle = null;
            for (int attempt = 0; handle == null; attempt++) {
                try {
                    handle = ScanService.get().start(new ScanRequest(cats, "pipeline"));
                } catch (ScanAlreadyRunningException e) {
                    if (attempt >= MAX_WAIT_FOR_RUNNING_SCANS) {
                        throw new AbortException("Another hygiene scan keeps running; giving up");
                    }
                    log.println("A hygiene scan is already running; waiting for it to finish...");
                    try {
                        e.running().get();
                    } catch (ExecutionException ignored) {
                        // that scan failed; ours is independent
                    }
                }
            }
            ScanRecord record;
            try {
                record = handle.result().get();
            } catch (InterruptedException e) {
                handle.cancel().cancel(); // the pipeline was aborted: stop our scan too
                throw e;
            } catch (ExecutionException e) {
                throw new AbortException(
                        "Hygiene scan failed: " + e.getCause().getClass().getName());
            }

            List<Finding> visible = FindingService.visible().stream()
                    .filter(f -> cats.isEmpty() || cats.contains(f.category()))
                    .toList();
            long open = visible.stream().filter(f -> f.status() == Status.OPEN).count();
            long high = visible.stream()
                    .filter(f -> f.status() == Status.OPEN && f.severity() == Severity.HIGH)
                    .count();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scanId", record.scanId());
            result.put("state", record.state().name());
            result.put("open", (int) open);
            result.put("high", (int) high);
            result.put("created", record.changes().created());
            result.put("resolved", record.changes().resolved());
            log.println("Hygiene scan " + record.state() + ": " + open + " open finding(s), " + high
                    + " HIGH severity, " + record.changes().created() + " new.");
            if (failOnHigh && high > 0) {
                throw new AbortException(high + " HIGH-severity hygiene finding(s)");
            }
            return result;
        }
    }

    @Extension(optional = true)
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "hygieneScan";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Run a Hygiene Auditor scan";
        }
    }
}
