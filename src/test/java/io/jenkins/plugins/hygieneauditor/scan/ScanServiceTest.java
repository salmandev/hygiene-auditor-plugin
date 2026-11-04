package io.jenkins.plugins.hygieneauditor.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.store.FindingStoreHolder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ScanServiceTest {

    @TestExtension
    public static class Stub extends TestStubs.StubCollector {}

    JenkinsRule j;

    static final ScanRequest PLUGINS = new ScanRequest(Set.of(Category.PLUGINS), "test");

    /** The real plugin collector shares the stub's category; keep these tests about the engine only. */
    @org.junit.jupiter.api.BeforeEach
    void isolateStub(JenkinsRule rule) {
        this.j = rule;
        HygieneAuditorConfiguration.get().setDisabledCollectors(List.of("plugins"));
    }

    @AfterEach
    void reset() {
        TestStubs.BODY.set(ctx -> {});
    }

    @Test
    void scanRunsAsSystemAtLowPriorityOnItsOwnThreadEvenWhenStartedByAnonymous() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<Integer> priority = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();
        TestStubs.BODY.set(ctx -> {
            auth.set(Jenkins.getAuthentication2().getName());
            priority.set(Thread.currentThread().getPriority());
            thread.set(Thread.currentThread().getName());
            ctx.emit(TestStubs.highPluginFinding());
        });
        ScanRecord r;
        try (ACLContext ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            r = ScanService.get().start(PLUGINS).result().get(30, TimeUnit.SECONDS);
        }
        assertEquals(ScanRecord.State.COMPLETED, r.state());
        assertEquals("SYSTEM", auth.get());
        assertEquals(Thread.MIN_PRIORITY, priority.get());
        assertEquals("Hygiene Auditor scan", thread.get());
        assertEquals(1, FindingStoreHolder.get().store().all().size());
        assertEquals(1, ScanService.get().history().size());
    }

    @Test
    void onlyOneScanAtATimeAndStatusIsVisibleWhileRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestStubs.BODY.set(ctx -> {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
        });
        ScanService svc = ScanService.get();
        ScanService.Handle h = svc.start(PLUGINS);
        assertTrue(started.await(30, TimeUnit.SECONDS));
        ScanAlreadyRunningException e = assertThrows(ScanAlreadyRunningException.class, () -> svc.start(PLUGINS));
        assertEquals(h.result(), e.running());
        ScanStatus st = svc.status().orElseThrow();
        assertEquals(h.scanId(), st.scanId());
        assertEquals(Set.of("test-stub"), st.collectors());
        release.countDown();
        h.result().get(30, TimeUnit.SECONDS);
        assertTrue(svc.status().isEmpty());
        svc.start(PLUGINS).result().get(30, TimeUnit.SECONDS); // a new scan can start afterwards
    }

    @Test
    void cancelStopsARunningScan() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        TestStubs.BODY.set(ctx -> {
            started.countDown();
            while (true) {
                ctx.checkpoint();
                Thread.sleep(5);
            }
        });
        ScanService svc = ScanService.get();
        ScanService.Handle h = svc.start(PLUGINS);
        assertTrue(started.await(30, TimeUnit.SECONDS));
        assertTrue(svc.cancel());
        assertEquals(
                ScanRecord.State.CANCELLED, h.result().get(30, TimeUnit.SECONDS).state());
        assertFalse(svc.cancel(), "nothing left to cancel");
    }

    @Test
    void disabledCollectorsAreNotRun() throws Exception {
        HygieneAuditorConfiguration.get().setDisabledCollectors(List.of("test-stub", "plugins"));
        assertThrows(IllegalArgumentException.class, () -> ScanService.get().start(PLUGINS));
    }

    @Test
    void periodicWorkFiresOncePerCronSlotAndNeverAtStartup() throws Exception {
        HygieneScanPeriodicWork work = ExtensionList.lookupSingleton(HygieneScanPeriodicWork.class);
        Instant t0 = Instant.now();
        HygieneAuditorConfiguration.get().setScanCron("* * * * *");
        assertFalse(work.tick(t0), "first look only sets the baseline");
        assertTrue(work.tick(t0.plusSeconds(150)));
        ScanService.get().status().ifPresent(s -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        HygieneAuditorConfiguration.get().setScanCron("");
        assertFalse(work.tick(t0.plusSeconds(600)), "blank cron means no scheduled scans");
    }

    @Test
    void cronSlotMathAndValidation() {
        ZoneId zone = ZoneId.systemDefault();
        long before =
                ZonedDateTime.of(2026, 3, 1, 2, 59, 0, 0, zone).toInstant().toEpochMilli();
        long after = ZonedDateTime.of(2026, 3, 1, 3, 1, 0, 0, zone).toInstant().toEpochMilli();
        long later = ZonedDateTime.of(2026, 3, 1, 3, 30, 0, 0, zone).toInstant().toEpochMilli();
        assertTrue(HygieneScanPeriodicWork.dueBetween("0 3 * * *", before, after));
        assertFalse(HygieneScanPeriodicWork.dueBetween("0 3 * * *", after, later), "slot already passed");
        assertFalse(HygieneScanPeriodicWork.dueBetween("", before, after));
        assertFalse(HygieneScanPeriodicWork.dueBetween("not a cron", before, after));
        assertTrue(HygieneScanPeriodicWork.dueBetween("# comment\n\n0 3 * * *", before, after));
        assertEquals(null, HygieneAuditorConfiguration.validateCron("H H * * 0"));
        assertTrue(HygieneAuditorConfiguration.validateCron("bogus") != null);
        assertEquals(null, HygieneAuditorConfiguration.validateCron(""));
    }
}
