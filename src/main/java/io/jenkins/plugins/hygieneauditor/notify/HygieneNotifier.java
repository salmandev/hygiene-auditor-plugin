package io.jenkins.plugins.hygieneauditor.notify;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;

/**
 * Extension point: receives new findings after a scan. Implementations must not block for long (the engine has no
 * timeout of its own) and must never put secrets in messages or exceptions.
 */
public abstract class HygieneNotifier implements ExtensionPoint {

    /** Stable id, used in logs. */
    @NonNull
    public abstract String getId();

    /** False when the channel is not set up; the engine then skips it silently. */
    public abstract boolean isConfigured();

    /** @throws Exception on any delivery problem; the engine logs it and carries on with the other notifiers */
    public abstract void notifyNewFindings(@NonNull NotificationBatch batch) throws Exception;

    public static ExtensionList<HygieneNotifier> all() {
        return ExtensionList.lookup(HygieneNotifier.class);
    }
}
