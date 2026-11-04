package io.jenkins.plugins.hygieneauditor.collector.credentials;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.fingerprints.AbstractCredentialsFingerprintFacet;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Fingerprint;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.ModelObject;
import hudson.model.Run;
import hudson.security.ACL;
import io.jenkins.plugins.hygieneauditor.collector.BudgetExceededException;
import io.jenkins.plugins.hygieneauditor.collector.ScanContext;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.FingerprintFacet;
import jenkins.model.Jenkins;

/**
 * Enumerates credentials through the Credentials plugin API. This is the only class that touches that API, so the
 * collector can still load when the plugin is absent.
 *
 * <p>Reads only id, type, scope and fingerprint timestamps. It never calls any secret accessor. Note that
 * {@code CredentialsProvider.getFingerprintOf} computes a digest of the credential inside the Credentials plugin;
 * that digest is not exposed to or retained by this plugin.
 *
 * <p>Enumerated: the Jenkins root and every item group (folder, multibranch project, ...). Not enumerated yet:
 * user-scoped credentials.
 */
final class CredentialInventory {

    record Result(List<CredentialRecord> credentials, int unreadableStores) {}

    private CredentialInventory() {}

    /**
     * Fingerprint tracking is on unless the JVM runs with {@code -DCredentialsProvider.fingerprintEnabled=false}.
     * The property name was read from the plugin's bytecode (it is not a public API): verify on your instance.
     */
    static boolean trackingEnabled() {
        return Boolean.parseBoolean(System.getProperty("CredentialsProvider.fingerprintEnabled", "true"));
    }

    /** Safety cap: more distinct jobs than this and the last-use lookup gives up (and makes no claim). */
    private static final int MAX_JOBS_PER_CREDENTIAL = 200;

    /** @param recentCutoffMillis a use at or after this instant counts as "recent"; lookups stop once one is found */
    static Result enumerate(ScanContext ctx, long recentCutoffMillis) throws InterruptedException {
        Jenkins jenkins = Jenkins.get();
        List<ModelObject> contexts = new ArrayList<>();
        contexts.add(jenkins);
        for (Item item : Items.allItems2(ACL.SYSTEM2, jenkins, Item.class)) {
            ctx.checkpoint();
            if (item instanceof ItemGroup) {
                contexts.add(item);
            }
        }
        boolean tracking = trackingEnabled();
        Map<String, CredentialRecord> out = new LinkedHashMap<>();
        int unreadable = 0;
        for (ModelObject context : contexts) {
            String owner = context instanceof Item i ? i.getFullName() : "";
            try {
                for (CredentialsStore store : CredentialsProvider.lookupStores(context)) {
                    if (store.getContext() != context) {
                        // lookupStores(folder) also returns the stores it inherits (Jenkins' own). Those belong to
                        // their real owner and are enumerated there: counting them here duplicated every root
                        // credential under every folder.
                        continue;
                    }
                    for (Domain domain : store.getDomains()) {
                        for (Credentials c : store.getCredentials(domain)) {
                            ctx.checkpoint();
                            if (c instanceof IdCredentials ic) {
                                CredentialRecord r = record(owner, domain, ic, c, tracking, recentCutoffMillis);
                                out.putIfAbsent(r.subjectFullName(), r);
                            }
                        }
                    }
                }
            } catch (BudgetExceededException e) {
                throw e;
            } catch (RuntimeException e) {
                unreadable++; // a misbehaving third-party store must not abort the whole collector
            }
        }
        return new Result(List.copyOf(out.values()), unreadable);
    }

    /**
     * Folds build usages into {@code latest}. Returns false when the answer cannot be trusted (too many jobs, or a
     * discarded build record that could be hiding a recent use): the caller then makes no usage claim at all.
     */
    private static boolean lastBuildUse(Fingerprint fp, long[] latest, long cutoff) {
        int checked = 0;
        for (String jobName : fp.getJobs()) {
            if (latest[0] >= cutoff) {
                return true; // already known to be recently used
            }
            if (++checked > MAX_JOBS_PER_CREDENTIAL) {
                return false;
            }
            Job<?, ?> job = Jenkins.get().getItemByFullName(jobName, Job.class);
            Fingerprint.RangeSet usages = fp.getRangeSet(jobName);
            if (job == null || usages.isEmpty()) {
                continue; // deleted job: it cannot have used the credential recently
            }
            Run<?, ?> build = job.getBuildByNumber(usages.max() - 1); // RangeSet.max() is exclusive
            if (build != null) {
                latest[0] = Math.max(latest[0], build.getTimeInMillis());
            } else {
                // The newest using build was discarded. Its use predates every retained build, so it is only
                // provably old if the oldest retained build is itself older than the cutoff.
                Run<?, ?> first = job.getFirstBuild();
                if (first != null && first.getTimeInMillis() >= cutoff) {
                    return false;
                }
            }
        }
        return true;
    }

    @NonNull
    private static CredentialRecord record(
            String owner, Domain domain, IdCredentials ic, Credentials c, boolean tracking, long cutoff) {
        String rawName = domain.getName();
        String domainName = rawName == null ? "_" : rawName;
        CredentialsScope scope = ic.getScope();
        boolean trackingOk = tracking;
        Instant lastUsed = null;
        if (tracking) {
            try {
                Fingerprint fp = CredentialsProvider.getFingerprintOf(c);
                if (fp != null) {
                    long[] latest = {fp.getTimestamp().getTime()}; // first recorded use: a use certainly happened then
                    for (FingerprintFacet facet : fp.getFacets()) { // item and node usages
                        if (facet instanceof AbstractCredentialsFingerprintFacet) {
                            latest[0] = Math.max(latest[0], facet.getTimestamp());
                        }
                    }
                    trackingOk = lastBuildUse(fp, latest, cutoff); // build usages
                    lastUsed = Instant.ofEpochMilli(latest[0]);
                }
            } catch (IOException | RuntimeException e) {
                trackingOk = false; // unreadable fingerprint is "no information", never "no use"
            }
        }
        return new CredentialRecord(
                owner,
                domainName,
                ic.getId(),
                c.getDescriptor().getDisplayName(),
                scope == null ? "UNKNOWN" : scope.name(),
                trackingOk,
                lastUsed);
    }
}
