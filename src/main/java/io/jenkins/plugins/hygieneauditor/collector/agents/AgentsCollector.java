package io.jenkins.plugins.hygieneauditor.collector.agents;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.slaves.EphemeralNode;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SimpleScheduledRetentionStrategy;
import io.jenkins.plugins.hygieneauditor.Messages;
import io.jenkins.plugins.hygieneauditor.collector.CollectorResult;
import io.jenkins.plugins.hygieneauditor.collector.ConfidenceScorer;
import io.jenkins.plugins.hygieneauditor.collector.HygieneCollector;
import io.jenkins.plugins.hygieneauditor.collector.ScanContext;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;

/**
 * Finds permanent agents that have been offline for a long time. Finding type: {@code agent.offline}.
 *
 * <p>Honest limits, all recorded in the evidence:
 * <ul>
 *   <li>The offline duration comes from {@link OfflineCause#getTimestamp()}, which restarts when the controller
 *       restarts. The scan can therefore under-report, never over-report, how long an agent was offline.
 *   <li>Agents on on-demand or scheduled retention strategies are offline by design: confidence is lowered.
 *   <li>Never-used agents, unrequested labels and unprovisioned cloud templates are not covered yet.
 * </ul>
 */
@Extension
public class AgentsCollector extends HygieneCollector {
    public static final String ID = "agents";
    private static final String SRC = "agent-state";

    /** Null means: read the global configuration at scan time. */
    private final AgentsSettings fixedSettings;

    public AgentsCollector() {
        this.fixedSettings = null;
    }

    AgentsCollector(AgentsSettings settings) {
        this.fixedSettings = settings;
    }

    private AgentsSettings settings() {
        return fixedSettings != null
                ? fixedSettings
                : io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration.get()
                        .agentsSettings();
    }

    @NonNull
    @Override
    public String getId() {
        return ID;
    }

    @NonNull
    @Override
    public Category getCategory() {
        return Category.AGENTS;
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return Messages.AgentsCollector_DisplayName();
    }

    @NonNull
    @Override
    public CollectorResult collect(@NonNull ScanContext ctx) throws InterruptedException {
        Instant now = ctx.clock().instant();
        for (Computer c : Jenkins.get().getComputers()) {
            ctx.checkpoint();
            Node node = c.getNode();
            if (node == null || node instanceof Jenkins || node instanceof EphemeralNode || !c.isOffline()) {
                continue; // built-in node, cloud/ephemeral agents, deleted nodes, and agents that are online
            }
            examine(ctx, c, now);
        }
        return CollectorResult.complete();
    }

    private void examine(ScanContext ctx, Computer c, Instant now) {
        OfflineCause cause = c.getOfflineCause();
        long ts = cause == null ? 0 : cause.getTimestamp();
        List<Evidence> ev = new ArrayList<>();
        int caveats = 0;
        Severity severity;

        if (ts > 0) {
            long days = Duration.between(Instant.ofEpochMilli(ts), now).toDays();
            if (days < settings().offlineDays()) {
                return;
            }
            severity = Severity.LOW;
            ev.add(new Evidence(SRC, "Offline for " + days + " days", 60));
            if (days >= 3L * settings().offlineDays()) {
                ev.add(new Evidence(
                        SRC,
                        "Offline for more than three times the threshold ("
                                + settings().offlineDays() + " days)",
                        15));
            }
            ev.add(new Evidence(
                    SRC,
                    "Offline duration is measured since the offline cause was recorded and restarts when the controller restarts",
                    0));
        } else {
            severity = Severity.INFO;
            ev.add(new Evidence(SRC, "Offline, but the time it went offline is not known", 20));
        }
        if (c.isTemporarilyOffline()) {
            ev.add(new Evidence(SRC, "Marked temporarily offline: " + describe(c), 0));
        }
        RetentionStrategy<?> rs = c.getRetentionStrategy();
        if (rs instanceof RetentionStrategy.Demand || rs instanceof SimpleScheduledRetentionStrategy) {
            ev.add(new Evidence(
                    SRC, "Uses an on-demand/scheduled retention strategy, so being offline is normal", -30));
            caveats = 1;
        }
        ev.add(new Evidence(SRC, "Not checked: whether any job still targets this agent by name or label", 0));

        ctx.emit(new FindingCandidate(
                "agent.offline",
                Category.AGENTS,
                Subject.of("agent", c.getName(), c.getDisplayName()),
                severity,
                ConfidenceScorer.score(ev, caveats),
                ev,
                Messages.AgentsCollector_SuggestedAction()));
    }

    private static String describe(Computer c) {
        String reason = c.getOfflineCauseReason();
        return reason.isBlank() ? "no reason given" : reason;
    }
}
