package io.jenkins.plugins.hygieneauditor.scan;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.scheduler.CronTab;
import hudson.scheduler.Hash;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration;
import java.io.IOException;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Wakes up every few minutes and starts a scan if a configured cron slot has passed since the last check. A missed
 * slot (controller was down) is skipped, never replayed. The scan itself runs on {@link ScanService}'s thread, so
 * this periodic task returns immediately.
 */
@Extension
public class HygieneScanPeriodicWork extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(HygieneScanPeriodicWork.class.getName());

    private long lastCheckedMillis = -1;

    public HygieneScanPeriodicWork() {
        super("Hygiene Auditor scheduled scan");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(5);
    }

    @Override
    protected void execute(@NonNull TaskListener listener) throws IOException, InterruptedException {
        tick(Instant.now());
    }

    /** Package-private for tests. Returns true if a scan was started. */
    synchronized boolean tick(Instant now) {
        long nowMillis = now.toEpochMilli();
        long previous = lastCheckedMillis;
        lastCheckedMillis = nowMillis;
        if (previous < 0) {
            return false; // first look after startup: establish a baseline, never fire immediately
        }
        Jenkins j = Jenkins.get();
        if (j.isQuietingDown() || j.isTerminating()) {
            return false;
        }
        if (!dueBetween(HygieneAuditorConfiguration.get().getScanCron(), previous, nowMillis)) {
            return false;
        }
        try {
            ScanService.get().start(new ScanRequest(java.util.Set.of(), "scheduled"));
            return true;
        } catch (ScanAlreadyRunningException e) {
            LOGGER.log(Level.INFO, "Scheduled hygiene scan skipped: a scan is already running");
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Scheduled hygiene scan could not start: {0}",
                    e.getClass().getName());
        }
        return false;
    }

    /** True if any cron line has a fire time in (fromMillis, toMillis]. Invalid lines are ignored. */
    static boolean dueBetween(String spec, long fromMillis, long toMillis) {
        if (spec == null || spec.isBlank()) {
            return false;
        }
        int line = 0;
        for (String l : spec.split("\\R")) {
            line++;
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            try {
                CronTab tab = new CronTab(t, line, Hash.from("hygiene-auditor"));
                Calendar cal = Calendar.getInstance(TimeZone.getDefault());
                cal.setTimeInMillis(toMillis);
                Calendar floor = tab.floor(cal);
                if (floor != null && floor.getTimeInMillis() > fromMillis) {
                    return true;
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "Ignoring invalid cron line {0}", line);
            }
        }
        return false;
    }
}
