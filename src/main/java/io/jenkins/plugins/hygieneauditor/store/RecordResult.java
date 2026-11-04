package io.jenkins.plugins.hygieneauditor.store;

import java.util.List;

/**
 * Counts describing what a scan changed in the store.
 *
 * @param newOrReopenedIds ids of findings that appeared for the first time or came back after being resolved or
 *     after a snooze expired: exactly the ones worth notifying about
 */
public record RecordResult(
        int created, int updated, int reopened, int resolved, int evicted, List<String> newOrReopenedIds) {
    public static final RecordResult EMPTY = new RecordResult(0, 0, 0, 0, 0, List.of());

    public RecordResult {
        newOrReopenedIds = List.copyOf(newOrReopenedIds);
    }
}
