package io.jenkins.plugins.hygieneauditor.notify;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.Status;
import io.jenkins.plugins.hygieneauditor.scan.ScanRecord;
import io.jenkins.plugins.hygieneauditor.security.Redactor;
import io.jenkins.plugins.hygieneauditor.store.FindingStore;
import io.jenkins.plugins.hygieneauditor.store.FindingStoreHolder;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * After a scan: works out which findings are <em>new</em> (first seen, or back after being resolved or after a snooze)
 * at or above the configured severity, and offers them to every configured notifier as one batch. A finding that stays
 * open is announced once, not on every scan. A failing channel never affects the scan or the other channels.
 */
public final class NotificationService {
    private static final Logger LOGGER = Logger.getLogger(NotificationService.class.getName());

    private NotificationService() {}

    /** @return the number of findings announced (0 if nothing qualified) */
    public static int dispatch(@NonNull ScanRecord scan) {
        try {
            List<String> ids = scan.changes().newOrReopenedIds();
            if (ids.isEmpty()) {
                return 0;
            }
            HygieneAuditorConfiguration cfg = HygieneAuditorConfiguration.get();
            FindingStore store = FindingStoreHolder.get().store();
            List<Finding> fresh = ids.stream()
                    .map(store::get)
                    .flatMap(Optional::stream)
                    .filter(f -> f.status() == Status.OPEN)
                    .filter(f -> f.severity().compareTo(cfg.getNotifyMinSeverity()) >= 0)
                    .sorted(Comparator.comparing(Finding::severity).reversed().thenComparing(Finding::id))
                    .toList();
            if (fresh.isEmpty()) {
                return 0;
            }
            NotificationBatch batch =
                    new NotificationBatch(fresh, scan, Jenkins.get().getRootUrl());
            for (HygieneNotifier n : HygieneNotifier.all()) {
                deliver(n, batch);
            }
            return fresh.size();
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Notification dispatch failed: {0}", Redactor.describe(e));
            return 0;
        }
    }

    private static void deliver(HygieneNotifier n, NotificationBatch batch) {
        try {
            if (!n.isConfigured()) {
                return;
            }
            n.notifyNewFindings(batch);
            LOGGER.log(Level.INFO, "Notified {0} about {1} new finding(s)", new Object[] {
                n.getId(), batch.findings().size()
            });
        } catch (Exception | LinkageError e) {
            LOGGER.log(Level.WARNING, "Notifier {0} failed: {1}", new Object[] {n.getId(), Redactor.describe(e)});
        }
    }
}
