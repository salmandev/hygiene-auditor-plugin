package io.jenkins.plugins.hygieneauditor.collector;

import io.jenkins.plugins.hygieneauditor.model.Finding;
import io.jenkins.plugins.hygieneauditor.scan.ScanRecord;
import io.jenkins.plugins.hygieneauditor.scan.ScanRunner;
import io.jenkins.plugins.hygieneauditor.scan.ScanSettings;
import io.jenkins.plugins.hygieneauditor.store.FindingStore;
import io.jenkins.plugins.hygieneauditor.store.RetentionPolicy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Runs a single collector through the real {@link ScanRunner} against the current Jenkins. */
public final class CollectorTestSupport {
    public final FindingStore store;
    public ScanRecord last;

    public CollectorTestSupport(Path dir) throws Exception {
        this.store = FindingStore.open(dir, RetentionPolicy.defaults());
    }

    /** Scans as if {@code daysFromNow} days had passed (file/offline timestamps stay real). */
    public List<Finding> scan(HygieneCollector c, long daysFromNow) throws Exception {
        return scan(c, daysFromNow, ScanSettings.defaults());
    }

    public List<Finding> scan(HygieneCollector c, long daysFromNow, ScanSettings settings) throws Exception {
        Clock clock = Clock.offset(Clock.system(ZoneOffset.UTC), Duration.ofDays(daysFromNow));
        last = new ScanRunner(store, clock, System::nanoTime, ProgressSink.NONE)
                .run("test", List.of(c), settings, new CancelToken());
        return store.all().stream()
                .filter(f -> f.status() != io.jenkins.plugins.hygieneauditor.model.Status.RESOLVED)
                .toList();
    }

    public static Optional<Finding> find(List<Finding> findings, String type, String fullName) {
        return findings.stream()
                .filter(f -> f.type().equals(type) && f.subject().fullName().equals(fullName))
                .findFirst();
    }
}
