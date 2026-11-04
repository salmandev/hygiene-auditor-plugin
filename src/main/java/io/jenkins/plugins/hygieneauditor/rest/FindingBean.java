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
