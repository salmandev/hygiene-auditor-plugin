package io.jenkins.plugins.hygieneauditor.security;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.security.AccessControlled;

/** Isolates the Credentials plugin class reference so {@link FindingAccess} loads without that plugin. */
final class CredentialsAccess {
    private CredentialsAccess() {}

    static boolean canView(AccessControlled context) {
        return context.hasPermission(CredentialsProvider.VIEW);
    }
}
