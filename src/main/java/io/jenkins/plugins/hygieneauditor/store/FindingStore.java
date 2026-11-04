package io.jenkins.plugins.hygieneauditor.store;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.Status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Compact, versioned, bounded JSON persistence of findings under a single directory
 * ({@code $JENKINS_HOME/hygiene-auditor/}). All mutations are atomic: memory is only updated after the file
 * has been written successfully.
 */
public final class FindingStore {
    private static final Logger LOGGER = Logger.getLogger(FindingStore.class.getName());
    private static final Gson GSON = new Gson();

    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "findings.json";

    private final Path file;
    private volatile RetentionPolicy retention;
    private final List<SchemaMigrator.Step> migrations;
    private Map<String, Finding> findings = new LinkedHashMap<>();

    private FindingStore(Path dir, RetentionPolicy retention, List<SchemaMigrator.Step> migrations) {
        this.file = dir.resolve(FILE_NAME);
        this.retention = retention;
        this.migrations = List.copyOf(migrations);
    }

    public static FindingStore open(@NonNull Path dir, @NonNull RetentionPolicy retention) throws IOException {
        return open(dir, retention, List.of());
    }

    /** @param migrations registered upgrade steps; production passes none until schema 2 exists */
    static FindingStore open(
            @NonNull Path dir, @NonNull RetentionPolicy retention, List<SchemaMigrator.Step> migrations)
            throws IOException {
        Files.createDirectories(dir);
        FindingStore store = new FindingStore(dir, retention, migrations);
        store.load();
        return store;
    }

    /** Applies new retention limits; takes effect at the next scan. */
    public void setRetention(@NonNull RetentionPolicy retention) {
        this.retention = retention;
    }

    // ---- reads ----

    @NonNull
    public synchronized List<Finding> all() {
        return List.copyOf(findings.values());
    }

    @NonNull
    public synchronized Optional<Finding> get(@NonNull String id) {
        return Optional.ofNullable(findings.get(id));
    }

    // ---- writes ----

    /**
     * Merges one scan's observations into the store.
     *
     * @param observed findings seen in this scan (ids may repeat; last wins)
     * @param fullyScannedCollectors ids of collectors that completed without hitting a cap or failing; only their
     *     unseen findings are marked RESOLVED
     */
    @NonNull
    public synchronized RecordResult recordScan(
            @NonNull Collection<Finding> observed, @NonNull Set<String> fullyScannedCollectors, @NonNull Instant now)
            throws IOException {
        Map<String, Finding> next = new LinkedHashMap<>(findings);
        Map<String, Finding> seen = new LinkedHashMap<>();
        for (Finding f : observed) {
            seen.put(f.id(), f);
        }
        int created = 0;
        int updated = 0;
        int reopened = 0;
        int resolved = 0;
        List<String> fresh = new ArrayList<>();
        for (Finding f : seen.values()) {
            Finding existing = next.get(f.id());
            if (existing == null) {
                next.put(f.id(), f.firstObservedAt(now));
                created++;
                fresh.add(f.id());
            } else {
                Finding merged = existing.mergeObservation(f, now);
                if (merged.status() == Status.OPEN
                        && existing.status() != Status.OPEN
                        && existing.status() != Status.ACKNOWLEDGED) {
                    reopened++;
                    fresh.add(f.id());
                } else {
                    updated++;
                }
                next.put(f.id(), merged);
            }
        }
        for (Finding f : new ArrayList<>(next.values())) {
            if (!seen.containsKey(f.id())
                    && f.status() != Status.RESOLVED
                    && fullyScannedCollectors.contains(f.collectorId())) {
                next.put(f.id(), f.resolved(now));
                resolved++;
            }
        }
        int evicted = prune(next, now);
        save(next);
        findings = next;
        fresh.removeIf(id -> !next.containsKey(id)); // evicted in the same pass
        return new RecordResult(created, updated, reopened, resolved, evicted, fresh);
    }

    /**
     * User-driven status change. RESOLVED cannot be set by users; IGNORED requires a reason; a snooze must be in
     * the future.
     */
    @NonNull
    public synchronized Optional<Finding> setStatus(
            @NonNull String id,
            @NonNull Status status,
            @CheckForNull String reason,
            @CheckForNull Instant snoozeUntil,
            @CheckForNull String by,
            @NonNull Instant now)
            throws IOException {
        if (status == Status.RESOLVED) {
            throw new IllegalArgumentException("RESOLVED is set by the scan engine only");
        }
        if (status == Status.IGNORED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A reason is required to ignore a finding");
        }
        if (snoozeUntil != null && (status != Status.IGNORED || !snoozeUntil.isAfter(now))) {
            throw new IllegalArgumentException("snoozeUntil must be in the future and only applies to IGNORED");
        }
        Finding existing = findings.get(id);
        if (existing == null) {
            return Optional.empty();
        }
        Finding updated = existing.withStatus(status, reason, snoozeUntil, by, now);
        Map<String, Finding> next = new LinkedHashMap<>(findings);
        next.put(id, updated);
        save(next);
        findings = next;
        return Optional.of(updated);
    }

    // ---- retention ----

    /** Removes expired RESOLVED findings, then evicts down to the cap. Returns the number removed. */
    private int prune(Map<String, Finding> map, Instant now) {
        int before = map.size();
        Instant cutoff = now.minus(Duration.ofDays(retention.resolvedRetentionDays()));
        map.values().removeIf(f -> {
            Instant changedAt = f.statusChangedAt();
            return f.status() == Status.RESOLVED && (changedAt == null ? f.lastSeen() : changedAt).isBefore(cutoff);
        });
        int excess = map.size() - retention.maxFindings();
        if (excess > 0) {
            // Evict least valuable first: RESOLVED, then OPEN (low severity, old), then user-touched ones last.
            List<Finding> order = new ArrayList<>(map.values());
            order.sort(Comparator.comparingInt((Finding f) -> rank(f.status()))
                    .thenComparing(Finding::severity)
                    .thenComparing(Finding::lastSeen));
            for (int i = 0; i < excess; i++) {
                map.remove(order.get(i).id());
            }
            LOGGER.log(Level.WARNING, "Finding cap {0} exceeded; evicted {1} findings", new Object[] {
                retention.maxFindings(), excess
            });
        }
        return before - map.size();
    }

    private static int rank(Status s) {
        return switch (s) {
            case RESOLVED -> 0;
            case OPEN -> 1;
            case ACKNOWLEDGED -> 2;
            case IGNORED -> 3;
        };
    }

    // ---- persistence ----

    private synchronized void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        JsonObject root;
        int version;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement v = root.get("schemaVersion");
            if (v == null || !v.isJsonPrimitive()) {
                throw new JsonParseException("missing schemaVersion");
            }
            version = v.getAsInt();
        } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            quarantine();
            return;
        }
        boolean migrated = version != SCHEMA_VERSION;
        root = SchemaMigrator.migrate(root, version, SCHEMA_VERSION, migrations);
        Map<String, Finding> loaded = new LinkedHashMap<>();
        int skipped = 0;
        JsonArray arr = root.has("findings") && root.get("findings").isJsonArray()
                ? root.getAsJsonArray("findings")
                : new JsonArray();
        for (JsonElement el : arr) {
            try {
                Finding f = FindingJson.fromJson(el.getAsJsonObject());
                loaded.put(f.id(), f);
            } catch (IllegalArgumentException | IllegalStateException e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            LOGGER.log(Level.WARNING, "Skipped {0} malformed entries in {1}", new Object[] {skipped, file});
        }
        findings = loaded;
        if (migrated) {
            save(loaded);
        }
    }

    private void quarantine() throws IOException {
        Path target = file.resolveSibling(FILE_NAME + ".corrupt-" + System.currentTimeMillis());
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.log(Level.WARNING, "Findings file was unreadable; moved to {0} and starting empty", target);
    }

    private void save(Map<String, Finding> map) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray arr = new JsonArray();
        for (Finding f : map.values()) {
            arr.add(FindingJson.toJson(f));
        }
        root.add("findings", arr);
        AtomicJson.write(file, root);
    }
}
