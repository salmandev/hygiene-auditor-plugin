package io.jenkins.plugins.hygieneauditor.security;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AccessControlled;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorPermissions;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;

/**
 * Decides which findings the <em>current</em> user may see. The scan sees everything (SYSTEM); every read goes
 * through here.
 *
 * <ul>
 *   <li>job findings: {@code HygieneAuditor.VIEW} and {@code Item.READ} on that job (VIEW may be granted on a folder)
 *   <li>credential findings: {@code HygieneAuditor.VIEW} and {@code Credentials/View} on the owning folder or root
 *   <li>everything else (agents, libraries, plugins, third-party kinds): {@code Jenkins.ADMINISTER}
 *   <li>subjects that no longer exist (deleted jobs/folders): {@code Jenkins.ADMINISTER}
 * </ul>
 */
public final class FindingAccess {
    private FindingAccess() {}

    /** Findings the current user may see, in the original order. */
    @NonNull
    public static List<Finding> filter(@NonNull Collection<Finding> findings) {
        Map<String, Boolean> memo = new HashMap<>();
        List<Finding> out = new ArrayList<>();
        for (Finding f : findings) {
            if (canView(f, memo)) {
                out.add(f);
            }
        }
        return out;
    }

    public static boolean canView(@NonNull Finding f) {
        return canView(f, new HashMap<>());
    }

    private static boolean canView(Finding f, Map<String, Boolean> memo) {
        String key = f.subject().kind() + ":"
                + (f.subject().kind().equals("credential")
                        ? owner(f)
                        : f.subject().fullName());
        return memo.computeIfAbsent(key, k -> compute(f));
    }

    private static boolean compute(Finding f) {
        Jenkins j = Jenkins.get();
        switch (f.subject().kind()) {
            case "job": {
                Item item = lookup(f.subject().fullName());
                return item == null
                        ? j.hasPermission(Jenkins.ADMINISTER)
                        : item.hasPermission(Item.READ) && item.hasPermission(HygieneAuditorPermissions.VIEW);
            }
            case "credential": {
                String owner = owner(f);
                AccessControlled ctx = owner.isEmpty() ? j : lookup(owner);
                if (ctx == null || j.getPlugin("credentials") == null) {
                    return j.hasPermission(Jenkins.ADMINISTER);
                }
                return ctx.hasPermission(HygieneAuditorPermissions.VIEW) && CredentialsAccess.canView(ctx);
            }
            default:
                return j.hasPermission(Jenkins.ADMINISTER);
        }
    }

    /** Credential subject full names are {@code ownerPath:domain:id}; item names cannot contain ':'. */
    static String owner(Finding f) {
        String n = f.subject().fullName();
        int i = n.indexOf(':');
        return i < 0 ? "" : n.substring(0, i);
    }

    /** Existence check as SYSTEM (getItemByFullName hides unreadable items); permissions are then checked as the user. */
    private static Item lookup(String fullName) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return Jenkins.get().getItemByFullName(fullName);
        }
    }
}
