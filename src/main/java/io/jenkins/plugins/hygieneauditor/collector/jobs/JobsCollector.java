package io.jenkins.plugins.hygieneauditor.collector.jobs;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.Run;
import hudson.security.ACL;
import io.jenkins.plugins.hygieneauditor.Messages;
import io.jenkins.plugins.hygieneauditor.collector.BudgetExceededException;
import io.jenkins.plugins.hygieneauditor.collector.CollectorResult;
import io.jenkins.plugins.hygieneauditor.collector.ConfidenceScorer;
import io.jenkins.plugins.hygieneauditor.collector.HygieneCollector;
import io.jenkins.plugins.hygieneauditor.collector.ScanContext;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

/**
 * Finds jobs that look abandoned: never built, not built for a long time, or disabled for a long time.
 *
 * <p>Cheap by design: reads only {@code getLastBuild()} (one build record per job, never the full history) and the
 * config file's modification time, which serves as a proxy for the job's age / disabled-since date.
 *
 * <p>Finding types: {@code job.never_built}, {@code job.stale}, {@code job.disabled}. A disabled job yields only
 * {@code job.disabled} (carrying its build evidence) so that one job does not produce several findings.
 */
@Extension
public class JobsCollector extends HygieneCollector {
    private static final Logger LOGGER = Logger.getLogger(JobsCollector.class.getName());
    public static final String ID = "jobs";
    private static final String SRC = "job-metadata";

    /** Null means: read the global configuration at scan time. */
    private final JobsSettings fixedSettings;

    public JobsCollector() {
        this.fixedSettings = null;
    }

    JobsCollector(JobsSettings settings) {
        this.fixedSettings = settings;
    }

    private JobsSettings settings() {
        return fixedSettings != null
                ? fixedSettings
                : io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration.get()
                        .jobsSettings();
    }

    @NonNull
    @Override
    public String getId() {
        return ID;
    }

    @NonNull
    @Override
    public Category getCategory() {
        return Category.JOBS;
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return Messages.JobsCollector_DisplayName();
    }

    @NonNull
    @Override
    public CollectorResult collect(@NonNull ScanContext ctx) throws InterruptedException {
        Instant now = ctx.clock().instant();
        int unreadable = 0;
        // allItems2 with SYSTEM2: the plain allItems(...) overloads filter by the *current* user's READ permission.
        for (Job<?, ?> job : Items.allItems2(ACL.SYSTEM2, Jenkins.get(), Job.class)) {
            ctx.checkpoint();
            try {
                examine(ctx, job, now);
            } catch (BudgetExceededException e) {
                throw e;
            } catch (RuntimeException e) {
                unreadable++; // one broken job must not abort the scan; details are deliberately not logged
                LOGGER.log(
                        Level.FINE, "Could not examine a job: {0}", e.getClass().getName());
            }
        }
        return unreadable == 0
                ? CollectorResult.complete()
                : CollectorResult.partial(unreadable + " job(s) could not be examined");
    }

    private void examine(ScanContext ctx, Job<?, ?> job, Instant now) {
        Run<?, ?> last = job.getLastBuild();
        if (last != null && last.isBuilding()) {
            return; // actively used
        }
        boolean disabled = job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> p && p.isDisabled();
        Instant configModified = configModified(job);
        Instant lastBuildAt = last == null ? null : Instant.ofEpochMilli(last.getStartTimeInMillis());
        boolean noBuildRecords = last == null;
        boolean historyDiscarded = noBuildRecords && job.getNextBuildNumber() > 1;

        List<Evidence> ev = new ArrayList<>();
        String type;
        int caveats = 0;

        if (disabled) {
            long disabledFor = configModified == null ? -1 : days(configModified, now);
            if (disabledFor < settings().disabledDays()) {
                return;
            }
            type = "job.disabled";
            ev.add(new Evidence(
                    SRC,
                    "Disabled; configuration unchanged for " + disabledFor
                            + " days (config modification time is used as 'disabled since')",
                    50));
            addBuildEvidence(
                    ev,
                    lastBuildAt,
                    historyDiscarded,
                    noBuildRecords,
                    now,
                    settings().disabledDays(),
                    20);
        } else if (noBuildRecords && !historyDiscarded) {
            long age = configModified == null ? -1 : days(configModified, now);
            if (age < settings().neverBuiltGraceDays()) {
                return; // new, or age unknown: stay quiet
            }
            type = "job.never_built";
            ev.add(new Evidence(SRC, "Has never been built (next build number is 1)", 35));
            ev.add(new Evidence(SRC, "Configuration last modified " + age + " days ago", 30));
            if (age >= 365) {
                ev.add(new Evidence(SRC, "Never built in over a year", 15));
            }
        } else if (historyDiscarded) {
            type = "job.stale";
            ev.add(new Evidence(
                    SRC,
                    "No build records remain although builds were run before (next build number "
                            + job.getNextBuildNumber() + "); last activity unknown",
                    20));
            caveats = 1;
        } else {
            long since = days(lastBuildAt, now);
            if (since < settings().staleDays()) {
                return;
            }
            type = "job.stale";
            ev.add(new Evidence(SRC, "Last build started " + since + " days ago", 50));
            if (since >= 2L * settings().staleDays()) {
                ev.add(new Evidence(
                        SRC,
                        "More than twice the staleness threshold (" + settings().staleDays() + " days)",
                        20));
            }
        }
        ev.add(new Evidence(
                SRC, "Not checked: whether other jobs or external systems (webhooks, scripts) trigger this job", 0));

        ctx.emit(new FindingCandidate(
                type,
                Category.JOBS,
                Subject.of("job", job.getFullName(), job.getFullDisplayName()),
                Severity.LOW,
                ConfidenceScorer.score(ev, caveats),
                ev,
                Messages.JobsCollector_SuggestedAction()));
    }

    private static void addBuildEvidence(
            List<Evidence> ev,
            Instant lastBuildAt,
            boolean historyDiscarded,
            boolean noBuildRecords,
            Instant now,
            int threshold,
            int weight) {
        if (lastBuildAt != null) {
            long since = days(lastBuildAt, now);
            ev.add(new Evidence(SRC, "Last build started " + since + " days ago", since >= threshold ? weight : 0));
        } else if (historyDiscarded) {
            ev.add(new Evidence(SRC, "No build records remain; last activity unknown", 0));
        } else if (noBuildRecords) {
            ev.add(new Evidence(SRC, "Has never been built", weight));
        }
    }

    private static Instant configModified(Job<?, ?> job) {
        File f = Items.getConfigFile(job).getFile();
        long m = f.lastModified();
        return m <= 0 ? null : Instant.ofEpochMilli(m);
    }

    private static long days(Instant from, Instant to) {
        return Duration.between(from, to).toDays();
    }
}
