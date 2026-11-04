package io.jenkins.plugins.hygieneauditor;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import jenkins.model.Jenkins;

/**
 * Permissions, chained so that each implies the one below: ADMINISTER &gt; CONFIGURE &gt; SCAN &gt; VIEW.
 *
 * <ul>
 *   <li>VIEW: see findings, filtered by what the viewer may otherwise see (Item.READ, Credentials VIEW, ADMINISTER
 *       for global categories). Its scope is item-group, so it can be granted on a folder.
 *   <li>SCAN: start and cancel scans, acknowledge/ignore findings. Global only.
 *   <li>CONFIGURE: change plugin settings. Global only.
 * </ul>
 */
public final class HygieneAuditorPermissions {

    public static final PermissionGroup GROUP =
            new PermissionGroup(HygieneAuditorPermissions.class, Messages._HygieneAuditorPermissions_GroupTitle());

    public static final Permission CONFIGURE = new Permission(
            GROUP,
            "Configure",
            Messages._HygieneAuditorPermissions_Configure_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);

    public static final Permission SCAN = new Permission(
            GROUP, "Scan", Messages._HygieneAuditorPermissions_Scan_Description(), CONFIGURE, PermissionScope.JENKINS);

    public static final Permission VIEW = new Permission(
            GROUP, "View", Messages._HygieneAuditorPermissions_View_Description(), SCAN, PermissionScope.ITEM_GROUP);

    private HygieneAuditorPermissions() {}

    /** Forces class initialisation so the permission group shows up in authorization matrices from startup. */
    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void register() {
        VIEW.getId();
    }
}
