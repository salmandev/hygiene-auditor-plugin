package io.jenkins.plugins.hygieneauditor.ui;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Item;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import jenkins.model.Jenkins;

/**
 * Where, in Jenkins itself, the person can look at (and, if they are allowed, remove) the thing a finding is about.
 * The plugin never deletes anything: it only points at Jenkins' own page, which applies Jenkins' own permission check
 * and confirmation. Computed when a page is shown, so nothing is stored. Returns null whenever the object no longer
 * exists or the viewer could not open that page anyway.
 */
public final class SubjectLinks {

    /** @param path relative to the Jenkins root URL, no leading slash */
    public record Link(@NonNull String path, @NonNull String labelKey) {}

    private SubjectLinks() {}

    @CheckForNull
    public static Link of(@NonNull Subject s) {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        String name = s.fullName();
        switch (s.kind()) {
            case "job": {
                Item item = j.getItemByFullName(name);
                return item == null || !item.hasPermission(Item.READ) ? null : new Link(item.getUrl(), "job");
            }
            case "agent": {
                Computer c = j.getComputer(name);
                return c == null || name.isEmpty() || !c.hasPermission(Computer.CONFIGURE)
                        ? null
                        : new Link("computer/" + Util.rawEncode(name) + "/", "agent");
            }
            case "credential":
                return credential(j, name);
            case "plugin":
                return j.hasPermission(Jenkins.ADMINISTER)
                        ? new Link("manage/pluginManager/installed", "plugin")
                        : null;
            case "plugin_data":
                return j.hasPermission(Jenkins.ADMINISTER)
                        ? new Link("manage/administrativeMonitor/OldData/manage", "plugin_data")
                        : null;
            case "library":
            case "library_var":
            case "library_class":
                return library(j, name);
            default:
                return null;
        }
    }

    /** Subject names are {@code ownerPath:domain:id}; item names cannot contain ':' (see CredentialRecord). */
    @CheckForNull
    private static Link credential(Jenkins j, String name) {
        int a = name.indexOf(':');
        int b = a < 0 ? -1 : name.indexOf(':', a + 1);
        if (b < 0) {
            return null;
        }
        String owner = name.substring(0, a);
        String domain = name.substring(a + 1, b);
        String id = name.substring(b + 1);
        String tail = "domain/" + Util.rawEncode(domain) + "/credential/" + Util.rawEncode(id) + "/";
        if (owner.isEmpty()) {
            return j.hasPermission(CredentialsProvider.VIEW)
                    ? new Link("manage/credentials/store/system/" + tail, "credential")
                    : null;
        }
        Item folder = j.getItemByFullName(owner);
        return folder == null || !folder.hasPermission(CredentialsProvider.VIEW)
                ? null
                : new Link(folder.getUrl() + "credentials/store/folder/" + tail, "credential");
    }

    /** Library keys are {@code ownerPath:name[:member]}. */
    @CheckForNull
    private static Link library(Jenkins j, String name) {
        int a = name.indexOf(':');
        if (a < 0) {
            return null;
        }
        String owner = name.substring(0, a);
        if (owner.isEmpty()) {
            return j.hasPermission(Jenkins.MANAGE) ? new Link("manage/configure", "library") : null;
        }
        Item folder = j.getItemByFullName(owner);
        return folder == null || !folder.hasPermission(Item.CONFIGURE)
                ? null
                : new Link(folder.getUrl() + "configure", "library");
    }
}
