package io.jenkins.plugins.hygieneauditor.collector.credentials;

/**
 * @param staleUseDays a recorded last use older than this counts as evidence of non-use
 * @param maxFileBytes sources larger than this are skipped (and counted as unscanned)
 */
public record CredentialsSettings(int staleUseDays, long maxFileBytes) {
    public CredentialsSettings {
        if (staleUseDays < 1 || maxFileBytes < 1024) {
            throw new IllegalArgumentException("Invalid settings");
        }
    }

    public static CredentialsSettings defaults() {
        return new CredentialsSettings(180, 50L * 1024 * 1024);
    }
}
