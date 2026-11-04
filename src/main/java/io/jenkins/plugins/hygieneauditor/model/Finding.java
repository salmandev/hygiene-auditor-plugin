package io.jenkins.plugins.hygieneauditor.model;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.security.Redactor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable record of one thing the auditor believes is unused. "Changing" a finding always yields a new instance
 * with the same {@link #id()}.
 */
public record Finding(
        @NonNull String id,
        @NonNull String collectorId,
        @NonNull Category category,
        @NonNull String type,
        @NonNull Subject subject,
        @NonNull Severity severity,
        @NonNull Confidence confidence,
        @NonNull List<Evidence> evidence,
        @NonNull String suggestedAction,
        @NonNull Instant firstSeen,
        @NonNull Instant lastSeen,
        @NonNull Status status,
        @CheckForNull String statusReason,
        @CheckForNull Instant snoozeUntil,
        @CheckForNull String statusChangedBy,
        @CheckForNull Instant statusChangedAt) {

    public static final int MAX_EVIDENCE = 25;
    public static final int MAX_REASON = 500;

    public Finding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(collectorId, "collectorId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(suggestedAction, "suggestedAction");
        Objects.requireNonNull(firstSeen, "firstSeen");
        Objects.requireNonNull(lastSeen, "lastSeen");
        Objects.requireNonNull(status, "status");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        List<Evidence> ev = evidence;
        if (ev.size() > MAX_EVIDENCE) {
            ev = new ArrayList<>(ev.subList(0, MAX_EVIDENCE - 1));
            ev.add(new Evidence(
                    "hygiene-auditor",
                    "… " + (evidence.size() - (MAX_EVIDENCE - 1)) + " more evidence items omitted",
                    0));
        }
        evidence = List.copyOf(ev);
        statusReason = statusReason == null ? null : Redactor.redactAndTruncate(statusReason, MAX_REASON);
        statusChangedBy = statusChangedBy == null ? null : Redactor.redactAndTruncate(statusChangedBy, 128);
    }

    /** Builds the first observation of a candidate. */
    public static Finding observed(@NonNull FindingCandidate c, @NonNull String collectorId, @NonNull Instant now) {
        return new Finding(
                FindingIds.of(c.type(), c.subject()),
                collectorId,
                c.category(),
                c.type(),
                c.subject(),
                c.severity(),
                c.confidence(),
                c.evidence(),
                c.suggestedAction(),
                now,
                now,
                Status.OPEN,
                null,
                null,
                null,
                null);
    }

    /**
     * Folds a fresh observation into this stored finding: keeps identity, firstSeen and user decisions, refreshes
     * the analysis. RESOLVED findings reopen; IGNORED findings whose snooze has expired reopen.
     */
    public Finding mergeObservation(@NonNull Finding observed, @NonNull Instant now) {
        boolean reopen = status == Status.RESOLVED || (status == Status.IGNORED && isSnoozeExpired(now));
        return new Finding(
                id,
                observed.collectorId,
                observed.category,
                observed.type,
                observed.subject,
                observed.severity,
                observed.confidence,
                observed.evidence,
                observed.suggestedAction,
                firstSeen,
                now,
                reopen ? Status.OPEN : status,
                reopen ? null : statusReason,
                reopen ? null : snoozeUntil,
                reopen ? null : statusChangedBy,
                reopen ? now : statusChangedAt);
    }

    /** First observation of a candidate that is new to the store: timestamps are set to {@code now}. */
    public Finding firstObservedAt(@NonNull Instant now) {
        return new Finding(
                id,
                collectorId,
                category,
                type,
                subject,
                severity,
                confidence,
                evidence,
                suggestedAction,
                now,
                now,
                Status.OPEN,
                null,
                null,
                null,
                null);
    }

    public Finding resolved(@NonNull Instant now) {
        return new Finding(
                id,
                collectorId,
                category,
                type,
                subject,
                severity,
                confidence,
                evidence,
                suggestedAction,
                firstSeen,
                lastSeen,
                Status.RESOLVED,
                "No longer observed",
                null,
                null,
                now);
    }

    public Finding withStatus(
            @NonNull Status newStatus,
            @CheckForNull String reason,
            @CheckForNull Instant snooze,
            @CheckForNull String by,
            @NonNull Instant now) {
        return new Finding(
                id,
                collectorId,
                category,
                type,
                subject,
                severity,
                confidence,
                evidence,
                suggestedAction,
                firstSeen,
                lastSeen,
                newStatus,
                reason,
                snooze,
                by,
                now);
    }

    public boolean isSnoozeExpired(@NonNull Instant now) {
        return snoozeUntil != null && !snoozeUntil.isAfter(now);
    }
}
