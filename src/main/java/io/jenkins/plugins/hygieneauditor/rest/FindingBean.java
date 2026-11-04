package io.jenkins.plugins.hygieneauditor.rest;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Api;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorPermissions;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.security.stapler.StaplerAccessibleType;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/** Public JSON shape of a finding. Field names are API: do not rename. Timestamps are ISO-8601 UTC strings. */
@ExportedBean(defaultVisibility = 999)
@StaplerAccessibleType // routable as finding/<id>/
public class FindingBean {
    private final Finding f;
    private final String statusAction;

    public FindingBean(Finding f) {
        this(f, null);
    }

    /**
     * @param statusAction relative URL of the POST endpoint for changing status, or null where that is not offered
     *     (for example on the per-folder page)
     */
    public FindingBean(Finding f, String statusAction) {
        this.f = f;
        this.statusAction = statusAction;
    }

    /** Callers (the routing getters) have already checked that this finding is visible to the user. */
    public Api getApi() {
        return new Api(this);
    }

    /** Why we think so: the evidence that supports the conclusion (positive weight), strongest first. */
    public java.util.List<String> getWhyLines() {
        return f.evidence().stream()
                .filter(e -> e.weight() > 0)
                .sorted(java.util.Comparator.comparingInt(io.jenkins.plugins.hygieneauditor.model.Evidence::weight)
                        .reversed())
                .limit(6)
                .map(io.jenkins.plugins.hygieneauditor.model.Evidence::text)
                .toList();
    }

    /** What was not checked, and other caveats (zero weight). */
    public java.util.List<String> getCaveatLines() {
        return f.evidence().stream()
                .filter(e -> e.weight() == 0)
                .limit(4)
                .map(io.jenkins.plugins.hygieneauditor.model.Evidence::text)
                .toList();
    }

    /** One line for the table: the top supporting facts joined, shortened. */
    public String getSummaryLine() {
        String joined = String.join(" · ", getWhyLines().stream().limit(2).toList());
        if (joined.isEmpty()) {
            joined = f.evidence().isEmpty() ? "" : f.evidence().get(0).text();
        }
        return joined.length() <= 120 ? joined : joined.substring(0, 119) + "…";
    }

    /** Plain-language reading of the confidence level. */
    public String getConfidenceNote() {
        return switch (f.confidence()) {
            case HIGH -> io.jenkins.plugins.hygieneauditor.Messages.Confidence_Note_HIGH();
            case MEDIUM -> io.jenkins.plugins.hygieneauditor.Messages.Confidence_Note_MEDIUM();
            case LOW -> io.jenkins.plugins.hygieneauditor.Messages.Confidence_Note_LOW();
        };
    }

    /** True for the types a generated script can remove; others only appear in the checklist. */
    public boolean isRemovalSupported() {
        return switch (f.type()) {
            case "job.never_built", "job.stale", "job.disabled", "agent.offline", "credential.unused" -> true;
            default -> false;
        };
    }

    /** Path (relative to the Jenkins root) of Jenkins' own page for the subject, or null. Not exported to REST. */
    public String getSubjectPath() {
        var l = io.jenkins.plugins.hygieneauditor.ui.SubjectLinks.of(f.subject());
        return l == null ? null : l.path();
    }

    public String getSubjectLinkLabel() {
        var l = io.jenkins.plugins.hygieneauditor.ui.SubjectLinks.of(f.subject());
        return l == null ? null : io.jenkins.plugins.hygieneauditor.ui.Labels.openIn(l.kind());
    }

    public String getTypeLabel() {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.type(f.type());
    }

    public String getLastSeenShort() {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.shortTime(f.lastSeen());
    }

    public String getLastSeenAgo() {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.ago(f.lastSeen());
    }

    public String getFirstSeenShort() {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.shortTime(f.firstSeen());
    }

    public String getFirstSeenAgo() {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.ago(f.firstSeen());
    }

    public String getCategoryLabel() {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.category(f.category());
    }

    public String getSeverityLabel() {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.severity(f.severity());
    }

    public String getConfidenceLabel() {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.confidence(f.confidence());
    }

    public String getStatusLabel() {
        return io.jenkins.plugins.hygieneauditor.ui.Labels.status(f.status());
    }

    public String getStatusAction() {
        return Jenkins.get().hasPermission(HygieneAuditorPermissions.SCAN) ? statusAction : null;
    }

    @Exported
    public String getId() {
        return f.id();
    }

    @Exported
    public String getCollector() {
        return f.collectorId();
    }

    @Exported
    public String getCategory() {
        return f.category().name();
    }

    @Exported
    public String getType() {
        return f.type();
    }

    @Exported
    public String getSubjectKind() {
        return f.subject().kind();
    }

    @Exported
    public String getSubject() {
        return f.subject().fullName();
    }

    @Exported
    public String getSubjectDisplayName() {
        return f.subject().displayName();
    }

    @Exported
    public String getSeverity() {
        return f.severity().name();
    }

    @Exported
    public String getConfidence() {
        return f.confidence().name();
    }

    @Exported
    public List<EvidenceBean> getEvidence() {
        return f.evidence().stream().map(EvidenceBean::new).toList();
    }

    @Exported
    public String getSuggestedAction() {
        return f.suggestedAction();
    }

    @Exported
    public String getFirstSeen() {
        return f.firstSeen().toString();
    }

    @Exported
    public String getLastSeen() {
        return f.lastSeen().toString();
    }

    @Exported
    public String getStatus() {
        return f.status().name();
    }

    @Exported
    @CheckForNull
    public String getStatusReason() {
        return f.statusReason();
    }

    @Exported
    @CheckForNull
    public String getSnoozeUntil() {
        java.time.Instant snooze = f.snoozeUntil();
        return snooze == null ? null : snooze.toString();
    }
}
