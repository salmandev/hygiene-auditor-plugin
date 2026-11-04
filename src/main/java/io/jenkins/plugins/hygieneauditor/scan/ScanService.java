package io.jenkins.plugins.hygieneauditor.scan;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.init.Terminator;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration;
import io.jenkins.plugins.hygieneauditor.collector.CancelToken;
import io.jenkins.plugins.hygieneauditor.collector.HygieneCollector;
import io.jenkins.plugins.hygieneauditor.store.FindingStoreHolder;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;

/**
 * Runs at most one scan at a time on a dedicated minimum-priority thread, as SYSTEM, without using any build
 * executor. Callers decide whether they are <em>allowed</em> to start a scan; this class only runs it.
 */
@Extension
public class ScanService {
    private static final Logger LOGGER = Logger.getLogger(ScanService.class.getName());
    private static final int HISTORY = 20;

    /** Handle to a started scan. */
    public record Handle(String scanId, CancelToken cancel, CompletableFuture<ScanRecord> result) {}

    private static final class Active {
        final String scanId;
        final String trigger;
        final Instant startedAt;
        final List<String> collectors;
        final CancelToken cancel = new CancelToken();
        final CompletableFuture<ScanRecord> result = new CompletableFuture<>();
        final Map<String, Long> progress = new ConcurrentHashMap<>();

        Active(String scanId, String trigger, Instant startedAt, List<String> collectors) {
            this.scanId = scanId;
            this.trigger = trigger;
            this.startedAt = startedAt;
            this.collectors = collectors;
        }
    }

    private final Object lock = new Object();
    private Active active; // guarded by lock
    private final Deque<ScanRecord> history = new ArrayDeque<>(); // guarded by lock
    private ExecutorService executor; // guarded by lock

    @NonNull
    public static ScanService get() {
        return ExtensionList.lookupSingleton(ScanService.class);
    }

    /**
     * Starts a scan in the background.
     *
     * @throws ScanAlreadyRunningException if one is running
     * @throws IOException if the findings store cannot be opened
     * @throws IllegalArgumentException if no collector matches the request
     */
    @NonNull
    public Handle start(@NonNull ScanRequest request) throws ScanAlreadyRunningException, IOException {
        var store = FindingStoreHolder.get().store();
        HygieneAuditorConfiguration cfg = HygieneAuditorConfiguration.get();
        List<HygieneCollector> collectors = HygieneCollector.all().stream()
                .filter(c ->
                        request.categories().isEmpty() || request.categories().contains(c.getCategory()))
                .filter(c -> cfg.isCollectorEnabled(c.getId()))
                .sorted(Comparator.comparing(HygieneCollector::getId))
                .collect(Collectors.toList());
        if (collectors.isEmpty()) {
            throw new IllegalArgumentException("No enabled collector matches the requested categories");
        }
        synchronized (lock) {
            if (active != null) {
                throw new ScanAlreadyRunningException(active.result);
            }
            Active a = new Active(
                    java.util.UUID.randomUUID().toString(),
                    request.trigger(),
                    Instant.now(),
                    collectors.stream().map(HygieneCollector::getId).toList());
            active = a;
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "Hygiene Auditor scan");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
            }
            ScanRunner runner = new ScanRunner(
                    store,
                    Clock.systemUTC(),
                    System::nanoTime,
                    (id, n) -> a.progress.put(id, n),
                    cfg.ignorePolicy()::isExempt);
            executor.execute(() -> run(a, runner, collectors));
            return new Handle(a.scanId, a.cancel, a.result);
        }
    }

    private void run(Active a, ScanRunner runner, List<HygieneCollector> collectors) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // read everything, whoever asked
            ScanRecord record = runner.run(a.trigger, collectors, ScanSettings.defaults(), a.cancel);
            recordHistory(record);
            synchronized (lock) {
                history.addFirst(record);
                while (history.size() > HISTORY) {
                    history.removeLast();
                }
                active = null;
            }
            LOGGER.log(Level.INFO, "Hygiene scan {0} ({1}) finished: {2}, changes {3}", new Object[] {
                record.scanId(), record.trigger(), record.state(), record.changes()
            });
            a.result.complete(record);
            io.jenkins.plugins.hygieneauditor.notify.NotificationService.dispatch(record);
        } catch (IOException | RuntimeException | Error e) {
            synchronized (lock) {
                active = null;
            }
            LOGGER.log(Level.WARNING, "Hygiene scan {0} failed: {1}", new Object[] {
                a.scanId, io.jenkins.plugins.hygieneauditor.security.Redactor.describe(e)
            });
            a.result.completeExceptionally(e);
        }
    }

    private void recordHistory(ScanRecord record) {
        try {
            var counts = io.jenkins.plugins.hygieneauditor.rest.FindingService.counts(
                    FindingStoreHolder.get().store().all());
            java.util.Map<String, Integer> cat = new java.util.LinkedHashMap<>();
            counts.byCategory().forEach((k, v) -> cat.put(k.name(), v));
            java.util.Map<String, Integer> sev = new java.util.LinkedHashMap<>();
            counts.bySeverity().forEach((k, v) -> sev.put(k.name(), v));
            FindingStoreHolder.get()
                    .history()
                    .append(new io.jenkins.plugins.hygieneauditor.store.ScanHistoryEntry(
                            record.scanId(),
                            record.trigger(),
                            record.end(),
                            record.state().name(),
                            cat,
                            sev));
        } catch (IOException | RuntimeException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Could not record scan history: {0}",
                    e.getClass().getName());
        }
    }

    /** Requests cancellation of the running scan. Returns false if none is running. */
    public boolean cancel() {
        synchronized (lock) {
            if (active == null) {
                return false;
            }
            active.cancel.cancel();
            return true;
        }
    }

    @NonNull
    public Optional<ScanStatus> status() {
        synchronized (lock) {
            if (active == null) {
                return Optional.empty();
            }
            return Optional.of(new ScanStatus(
                    active.scanId,
                    active.trigger,
                    active.startedAt,
                    Set.copyOf(active.collectors),
                    active.progress,
                    active.cancel.isCancelled()));
        }
    }

    @NonNull
    public List<ScanRecord> history() {
        synchronized (lock) {
            return List.copyOf(history);
        }
    }

    @CheckForNull
    public ScanRecord lastScan() {
        synchronized (lock) {
            return history.peekFirst();
        }
    }

    @Terminator
    public static void shutdown() {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return;
        }
        ScanService s =
                ExtensionList.lookup(ScanService.class).stream().findFirst().orElse(null);
        if (s != null) {
            synchronized (s.lock) {
                if (s.active != null) {
                    s.active.cancel.cancel();
                }
                if (s.executor != null) {
                    s.executor.shutdownNow();
                }
            }
        }
    }
}
