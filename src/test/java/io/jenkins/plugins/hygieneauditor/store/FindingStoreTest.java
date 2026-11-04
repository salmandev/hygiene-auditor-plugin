package io.jenkins.plugins.hygieneauditor.store;

import static io.jenkins.plugins.hygieneauditor.model.Fixtures.T0;
import static io.jenkins.plugins.hygieneauditor.model.Fixtures.candidate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Status;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindingStoreTest {
    @TempDir
    Path dir;

    FindingStore open() throws IOException {
        return FindingStore.open(dir, RetentionPolicy.defaults());
    }

    static Finding obs(String name, String collector, Instant t) {
        return Finding.observed(candidate(name), collector, t);
    }

    @Test
    void newFindingsAreStoredAndSurviveReload() throws Exception {
        FindingStore s = open();
        RecordResult r = s.recordScan(List.of(obs("a", "jobs", T0), obs("b", "jobs", T0)), Set.of("jobs"), T0);
        assertEquals(2, r.created());
        FindingStore reloaded = open();
        assertEquals(2, reloaded.all().size());
        Finding f = reloaded.all().get(0);
        assertEquals(Status.OPEN, f.status());
        assertEquals(T0, f.firstSeen());
        assertEquals("never built", f.evidence().get(0).text());
    }

    @Test
    void rescanKeepsFirstSeenAndUserStatus() throws Exception {
        FindingStore s = open();
        Finding f = obs("a", "jobs", T0);
        s.recordScan(List.of(f), Set.of("jobs"), T0);
        Instant t1 = T0.plus(Duration.ofDays(1));
        s.setStatus(f.id(), Status.ACKNOWLEDGED, "ok", null, "alice", t1);
        Instant t2 = t1.plus(Duration.ofDays(1));
        RecordResult r = s.recordScan(List.of(obs("a", "jobs", t2)), Set.of("jobs"), t2);
        assertEquals(1, r.updated());
        Finding got = s.get(f.id()).orElseThrow();
        assertEquals(T0, got.firstSeen());
        assertEquals(t2, got.lastSeen());
        assertEquals(Status.ACKNOWLEDGED, got.status());
    }

    @Test
    void unseenFindingIsResolvedOnlyWhenItsCollectorCompleted() throws Exception {
        FindingStore s = open();
        Finding a = obs("a", "jobs", T0);
        Finding b = obs("b", "agents", T0);
        s.recordScan(List.of(a, b), Set.of("jobs", "agents"), T0);
        Instant t1 = T0.plusSeconds(60);
        // jobs complete and saw nothing; agents only partial.
        RecordResult r = s.recordScan(List.of(), Set.of("jobs"), t1);
        assertEquals(1, r.resolved());
        assertEquals(Status.RESOLVED, s.get(a.id()).orElseThrow().status());
        assertEquals(Status.OPEN, s.get(b.id()).orElseThrow().status());
    }

    @Test
    void resolvedFindingReopensWhenSeenAgain() throws Exception {
        FindingStore s = open();
        Finding a = obs("a", "jobs", T0);
        s.recordScan(List.of(a), Set.of("jobs"), T0);
        s.recordScan(List.of(), Set.of("jobs"), T0.plusSeconds(1));
        RecordResult r = s.recordScan(List.of(obs("a", "jobs", T0)), Set.of("jobs"), T0.plusSeconds(2));
        assertEquals(1, r.reopened());
        assertEquals(Status.OPEN, s.get(a.id()).orElseThrow().status());
    }

    @Test
    void statusRules() throws Exception {
        FindingStore s = open();
        Finding a = obs("a", "jobs", T0);
        s.recordScan(List.of(a), Set.of("jobs"), T0);
        assertThrows(IllegalArgumentException.class, () -> s.setStatus(a.id(), Status.RESOLVED, "x", null, "u", T0));
        assertThrows(IllegalArgumentException.class, () -> s.setStatus(a.id(), Status.IGNORED, " ", null, "u", T0));
        assertThrows(
                IllegalArgumentException.class,
                () -> s.setStatus(a.id(), Status.IGNORED, "r", T0.minusSeconds(1), "u", T0));
        assertTrue(s.setStatus("nope", Status.ACKNOWLEDGED, null, null, "u", T0).isEmpty());
        s.setStatus(a.id(), Status.IGNORED, "password=hunter2 is rotated", T0.plusSeconds(10), "u", T0);
        Finding got = open().get(a.id()).orElseThrow();
        assertEquals(Status.IGNORED, got.status());
        assertFalse(got.statusReason().contains("hunter2"), "reasons are redacted too");
    }

    @Test
    void expiredResolvedFindingsArePruned() throws Exception {
        FindingStore s = FindingStore.open(dir, new RetentionPolicy(100, 7));
        Finding a = obs("a", "jobs", T0);
        s.recordScan(List.of(a), Set.of("jobs"), T0);
        s.recordScan(List.of(), Set.of("jobs"), T0.plus(Duration.ofDays(1))); // resolved
        RecordResult r = s.recordScan(List.of(), Set.of("jobs"), T0.plus(Duration.ofDays(9)));
        assertEquals(1, r.evicted());
        assertTrue(s.all().isEmpty());
    }

    @Test
    void capEvictsResolvedThenLowSeverityOpenBeforeUserDecisions() throws Exception {
        FindingStore s = FindingStore.open(dir, new RetentionPolicy(3, 30));
        FindingCandidate high = new FindingCandidate(
                "job.stale",
                Category.JOBS,
                Subject.of("job", "high", null),
                Severity.HIGH,
                Confidence.HIGH,
                List.of(new Evidence("t", "e", 1)),
                "");
        Finding h = Finding.observed(high, "jobs", T0);
        Finding ignored = obs("ign", "jobs", T0);
        s.recordScan(List.of(h, ignored), Set.of(), T0);
        s.setStatus(ignored.id(), Status.IGNORED, "keep", null, "u", T0);
        // two more LOW opens push us over the cap of 3
        RecordResult r = s.recordScan(List.of(obs("l1", "jobs", T0), obs("l2", "jobs", T0)), Set.of(), T0);
        assertEquals(1, r.evicted());
        assertEquals(3, s.all().size());
        assertTrue(s.get(h.id()).isPresent(), "HIGH survives");
        assertTrue(s.get(ignored.id()).isPresent(), "user decision survives");
    }

    @Test
    void corruptFileIsQuarantinedNotFatal() throws Exception {
        Files.writeString(dir.resolve(FindingStore.FILE_NAME), "{ not json");
        FindingStore s = open();
        assertTrue(s.all().isEmpty());
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")));
        }
        s.recordScan(List.of(obs("a", "jobs", T0)), Set.of(), T0); // store is usable afterwards
        assertEquals(1, open().all().size());
    }

    @Test
    void malformedEntriesAreSkippedButGoodOnesLoad() throws Exception {
        FindingStore s = open();
        s.recordScan(List.of(obs("a", "jobs", T0)), Set.of(), T0);
        Path f = dir.resolve(FindingStore.FILE_NAME);
        String json = Files.readString(f).replace("\"findings\":[", "\"findings\":[{\"id\":\"broken\"},");
        Files.writeString(f, json);
        assertEquals(1, open().all().size());
    }

    @Test
    void newerSchemaIsRejectedAndFileLeftUntouched() throws Exception {
        Path f = dir.resolve(FindingStore.FILE_NAME);
        String content = "{\"schemaVersion\":99,\"findings\":[]}";
        Files.writeString(f, content);
        assertThrows(UnsupportedSchemaException.class, this::open);
        assertEquals(content, Files.readString(f));
    }

    @Test
    void olderSchemaIsMigratedAndRewritten() throws Exception {
        // Simulate a future world: current schema 1, legacy files at schema 0 with a differently-named list.
        Path f = dir.resolve(FindingStore.FILE_NAME);
        FindingStore seed = open();
        seed.recordScan(List.of(obs("a", "jobs", T0)), Set.of(), T0);
        String v1 = Files.readString(f);
        String legacy = v1.replace("\"schemaVersion\":1", "\"schemaVersion\":0").replace("\"findings\"", "\"items\"");
        Files.writeString(f, legacy);

        SchemaMigrator.Step step = new SchemaMigrator.Step() {
            @Override
            public int from() {
                return 0;
            }

            @Override
            public JsonObject apply(JsonObject root) {
                root.add("findings", root.remove("items"));
                return root;
            }
        };
        FindingStore migrated = FindingStore.open(dir, RetentionPolicy.defaults(), List.of(step));
        assertEquals(1, migrated.all().size());
        assertTrue(Files.readString(f).contains("\"schemaVersion\":1"));
        // Missing step is an error, not silent data loss.
        Files.writeString(f, legacy);
        assertThrows(IOException.class, this::open);
    }

    @Test
    void noTempFileLeftBehind() throws Exception {
        open().recordScan(List.of(obs("a", "jobs", T0)), Set.of(), T0);
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(
                    List.of(FindingStore.FILE_NAME),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void persistedFileNeverContainsSecretsFromEvidence() throws Exception {
        FindingCandidate c = new FindingCandidate(
                "credential.unused",
                Category.CREDENTIALS,
                Subject.of("credential", "cred-1", null),
                Severity.MEDIUM,
                Confidence.LOW,
                List.of(new Evidence("scan", "config had password=hunter2 and Bearer abcdefgh12345678", 10)),
                "rotate token=hunter2");
        open().recordScan(List.of(Finding.observed(c, "credentials", T0)), Set.of(), T0);
        String raw = Files.readString(dir.resolve(FindingStore.FILE_NAME));
        assertFalse(raw.contains("hunter2"));
        assertFalse(raw.contains("abcdefgh12345678"));
    }
}
