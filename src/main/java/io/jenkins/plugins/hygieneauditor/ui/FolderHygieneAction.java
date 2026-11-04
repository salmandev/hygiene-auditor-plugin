package io.jenkins.plugins.hygieneauditor.ui;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Api;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorPermissions;
import io.jenkins.plugins.hygieneauditor.Messages;
import io.jenkins.plugins.hygieneauditor.rest.FindingBean;
import io.jenkins.plugins.hygieneauditor.rest.FindingQuery;
import io.jenkins.plugins.hygieneauditor.rest.FindingService;
import io.jenkins.plugins.hygieneauditor.rest.FindingsBean;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import jenkins.model.TransientActionFactory;
import jenkins.security.stapler.StaplerAccessibleType;
import org.kohsuke.stapler.Stapler;

/**
 * "Hygiene Auditor" tab on a folder, for people who hold {@code HygieneAuditor.VIEW} on that folder but nowhere
 * else. Shows only that folder's findings (further filtered by the viewer's permissions), read-only.
 */
public class FolderHygieneAction implements Action {
    private final AbstractFolder<?> folder;

    FolderHygieneAction(AbstractFolder<?> folder) {
        this.folder = folder;
    }

    @CheckForNull
    @Override
    public String getIconFileName() {
        return "symbol-shield-warning";
    }

    @Override
    public String getDisplayName() {
        return Messages.HygieneAuditorLink_DisplayName();
    }

    @Override
    public String getUrlName() {
        return "hygiene-auditor";
    }

    public AbstractFolder<?> getFolder() {
        return folder;
    }

    private FindingQuery query() {
        return FindingQuery.fromRequest(Stapler.getCurrentRequest2()).withFolder(folder.getFullName());
    }

    public FindingsPanel getPanel() throws IOException {
        folder.checkPermission(HygieneAuditorPermissions.VIEW);
        FindingQuery q = query();
        return new FindingsPanel(q, FindingService.query(q), false);
    }

    /** {@code <folder>/hygiene-auditor/findings/api/json} */
    public FindingsView getFindings() {
        folder.checkPermission(HygieneAuditorPermissions.VIEW);
        return new FindingsView(this);
    }

    /** {@code <folder>/hygiene-auditor/finding/<id>/}: only if it is visible to the user and inside this folder. */
    public FindingBean getFinding(String id) throws IOException {
        folder.checkPermission(HygieneAuditorPermissions.VIEW);
        return FindingService.visible(id)
                .filter(f -> FindingQuery.all().withFolder(folder.getFullName()).matches(f))
                .map(f -> new FindingBean(f, null))
                .orElse(null);
    }

    @StaplerAccessibleType
    public static final class FindingsView {
        private final FolderHygieneAction owner;

        FindingsView(FolderHygieneAction owner) {
            this.owner = owner;
        }

        public Api getApi() throws IOException {
            return FindingsBean.api(owner.query());
        }
    }

    @Extension(optional = true)
    public static class Factory extends TransientActionFactory<AbstractFolder> {
        @Override
        public Class<AbstractFolder> type() {
            return AbstractFolder.class;
        }

        @NonNull
        @Override
        public Collection<? extends Action> createFor(@NonNull AbstractFolder target) {
            return target.hasPermission(HygieneAuditorPermissions.VIEW)
                    ? List.of(new FolderHygieneAction(target))
                    : List.of();
        }
    }
}
