package io.jenkins.plugins.hygieneauditor.collector.credentials;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Metadata about one credential. Deliberately contains no secret, no description and no username.
 *
 * @param ownerPath full name of the owning folder, or empty for the Jenkins root. Item names cannot contain ':', so
 *     the subject full name {@code ownerPath:domain:id} can always be split at its first ':' to recover the owner
 * @param domain domain name, {@code _} for the global domain
 * @param tracking whether fingerprint tracking was enabled when scanned
 * @param lastUsed latest recorded use according to fingerprint facets; null if none
 */
public record CredentialRecord(
        @NonNull String ownerPath,
        @NonNull String domain,
        @NonNull String id,
        @NonNull String typeName,
        @NonNull String scope,
        boolean tracking,
        @CheckForNull Instant lastUsed) {

    @NonNull
    public String subjectFullName() {
        return ownerPath + ":" + domain + ":" + id;
    }

    @NonNull
    public String displayName() {
        return id + " (" + (ownerPath.isEmpty() ? "Jenkins" : ownerPath) + ", domain "
                + ("_".equals(domain) ? "global" : domain) + ")";
    }
}
