package io.jenkins.plugins.hygieneauditor.collector;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import io.jenkins.plugins.hygieneauditor.model.Category;

/**
 * Extension point: contributes findings for one area of a controller. Implementations must be read-only,
 * must call {@link ScanContext#checkpoint()} regularly, must never read or emit secret values, and must treat all
 * scanned content as untrusted (parse, never evaluate). Register with {@code @Extension}.
 */
public abstract class HygieneCollector implements ExtensionPoint {

    /** Stable id used in config, ignore rules and persisted findings, e.g. {@code jobs}. */
    @NonNull
    public abstract String getId();

    @NonNull
    public abstract Category getCategory();

    /** Localised name for the UI. */
    @NonNull
    public abstract String getDisplayName();

    /** Return false when a required optional plugin is absent; the collector is then reported as SKIPPED. */
    public boolean isApplicable() {
        return true;
    }

    /**
     * Runs the collection, emitting findings through {@code ctx}.
     *
     * @throws InterruptedException when the scan is cancelled (propagate from {@code ctx.checkpoint()})
     */
    @NonNull
    public abstract CollectorResult collect(@NonNull ScanContext ctx) throws InterruptedException;

    public static ExtensionList<HygieneCollector> all() {
        return ExtensionList.lookup(HygieneCollector.class);
    }
}
