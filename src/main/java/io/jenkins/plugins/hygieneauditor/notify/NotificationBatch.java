package io.jenkins.plugins.hygieneauditor.notify;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.scan.ScanRecord;
import java.util.List;

/**
 * What a notifier is told: the findings that appeared (or came back) in one scan and meet the severity threshold.
 *
 * @param instanceUrl Jenkins root URL with trailing slash, or null if it is not configured
 */
public record NotificationBatch(
        @NonNull List<Finding> findings,
        @NonNull ScanRecord scan,
        @CheckForNull String instanceUrl) {
    public NotificationBatch {
        findings = List.copyOf(findings);
    }

    /** Link to a finding's page, or null when no root URL is configured. */
    @CheckForNull
    public String linkTo(@NonNull Finding f) {
        return instanceUrl == null ? null : instanceUrl + "manage/hygiene-auditor/finding/" + f.id() + "/";
    }
}
