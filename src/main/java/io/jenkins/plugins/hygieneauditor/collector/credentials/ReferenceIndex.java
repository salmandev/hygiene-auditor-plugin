package io.jenkins.plugins.hygieneauditor.collector.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Accumulates what the static scan learned. Only IDs that are known credentials (candidates) are ever kept, so
 * memory is bounded by the number of credentials, not by the amount of scanned content.
 */
public final class ReferenceIndex {
    private final Set<String> candidates;
    private final Set<String> referenced = new HashSet<>();
    /** Number of dynamic references located at or below each folder path ("" = anywhere). */
    private final Map<String, Integer> dynamicUnder = new HashMap<>();

    private long configFiles;
    private long scripts;
    private long cascFiles;
    private long unscanned;
    private long dynamicTotal;

    public ReferenceIndex(@NonNull Set<String> candidateIds) {
        this.candidates = Set.copyOf(candidateIds);
    }

    public void markReferenced(@NonNull String id) {
        if (candidates.contains(id)) {
            referenced.add(id);
        }
    }

    /** Records a reference whose value cannot be known statically, found in the item at {@code location}. */
    public void markDynamic(@NonNull String location) {
        dynamicTotal++;
        dynamicUnder.merge("", 1, Integer::sum);
        int i = -1;
        while ((i = location.indexOf('/', i + 1)) >= 0) {
            dynamicUnder.merge(location.substring(0, i), 1, Integer::sum);
        }
        if (!location.isEmpty()) {
            dynamicUnder.merge(location, 1, Integer::sum);
        }
    }

    public boolean isReferenced(@NonNull String id) {
        return referenced.contains(id);
    }

    /**
     * Dynamic references that could resolve to a credential owned by {@code ownerPath}: those located in that
     * folder or below (credentials are visible downwards only). The root owner sees all of them.
     */
    public int dynamicInScope(@NonNull String ownerPath) {
        return dynamicUnder.getOrDefault(ownerPath, 0);
    }

    public void countConfigFile() {
        configFiles++;
    }

    public void countScript() {
        scripts++;
    }

    public void countCascFile() {
        cascFiles++;
    }

    public void countUnscanned() {
        unscanned++;
    }

    public long configFiles() {
        return configFiles;
    }

    public long scripts() {
        return scripts;
    }

    public long cascFiles() {
        return cascFiles;
    }

    public long unscanned() {
        return unscanned;
    }

    public long dynamicTotal() {
        return dynamicTotal;
    }
}
