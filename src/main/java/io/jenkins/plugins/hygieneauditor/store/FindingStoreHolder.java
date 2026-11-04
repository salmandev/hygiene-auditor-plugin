package io.jenkins.plugins.hygieneauditor.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import jenkins.model.Jenkins;

/** Owns the single {@link FindingStore} of this Jenkins instance, opened lazily under JENKINS_HOME. */
@Extension
public class FindingStoreHolder {
    private FindingStore store;
    private ScanHistoryStore history;

    @NonNull
    public static FindingStoreHolder get() {
        return ExtensionList.lookupSingleton(FindingStoreHolder.class);
    }

    @NonNull
    public synchronized ScanHistoryStore history() throws IOException {
        if (history == null) {
            history = ScanHistoryStore.open(Jenkins.get().getRootDir().toPath().resolve("hygiene-auditor"));
        }
        return history;
    }

    @NonNull
    public synchronized FindingStore store() throws IOException {
        if (store == null) {
            Path dir = Jenkins.get().getRootDir().toPath().resolve("hygiene-auditor");
            store = FindingStore.open(dir, RetentionPolicy.defaults());
        }
        store.setRetention(HygieneAuditorConfiguration.get().retentionPolicy());
        return store;
    }
}
