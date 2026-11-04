package io.jenkins.plugins.hygieneauditor.ui;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.Messages;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Status;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Localised display names for the enums. Stored data and REST always use the enum names. */
public final class Labels {
    private Labels() {}

    @NonNull
    public static String category(@NonNull Category c) {
        return switch (c) {
            case JOBS -> Messages.Label_Category_JOBS();
            case AGENTS -> Messages.Label_Category_AGENTS();
            case CREDENTIALS -> Messages.Label_Category_CREDENTIALS();
            case SHARED_LIBRARIES -> Messages.Label_Category_SHARED_LIBRARIES();
            case PLUGINS -> Messages.Label_Category_PLUGINS();
        };
    }

    @NonNull
    public static String severity(@NonNull Severity s) {
        return switch (s) {
            case INFO -> Messages.Label_Severity_INFO();
            case LOW -> Messages.Label_Severity_LOW();
            case MEDIUM -> Messages.Label_Severity_MEDIUM();
            case HIGH -> Messages.Label_Severity_HIGH();
        };
    }

    @NonNull
    public static String confidence(@NonNull Confidence c) {
        return switch (c) {
            case LOW -> Messages.Label_Confidence_LOW();
            case MEDIUM -> Messages.Label_Confidence_MEDIUM();
            case HIGH -> Messages.Label_Confidence_HIGH();
        };
    }

    @NonNull
    public static String status(@NonNull Status s) {
        return switch (s) {
            case OPEN -> Messages.Label_Status_OPEN();
            case ACKNOWLEDGED -> Messages.Label_Status_ACKNOWLEDGED();
            case IGNORED -> Messages.Label_Status_IGNORED();
            case RESOLVED -> Messages.Label_Status_RESOLVED();
        };
    }

    /** Label for an enum name coming from a bean/REST map, tolerant of unknown values. */
    @NonNull
    public static String category(@NonNull String name) {
        try {
            return category(Category.valueOf(name));
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    @NonNull
    public static String severity(@NonNull String name) {
        try {
            return severity(Severity.valueOf(name));
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    @NonNull
    public static String confidence(@NonNull String name) {
        try {
            return confidence(Confidence.valueOf(name));
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    @NonNull
    public static String status(@NonNull String name) {
        try {
            return status(Status.valueOf(name));
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    private static final DateTimeFormatter SHORT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    /** Plain-language name of a finding type; unknown (third-party) types show as they are. */
    @NonNull
    public static String type(@NonNull String type) {
        return switch (type) {
            case "job.never_built" -> Messages.Label_Type_job_never_built();
            case "job.stale" -> Messages.Label_Type_job_stale();
            case "job.disabled" -> Messages.Label_Type_job_disabled();
            case "agent.offline" -> Messages.Label_Type_agent_offline();
            case "credential.unused" -> Messages.Label_Type_credential_unused();
            case "library.unreferenced" -> Messages.Label_Type_library_unreferenced();
            case "library.unused_variable" -> Messages.Label_Type_library_unused_variable();
            case "library.unused_class" -> Messages.Label_Type_library_unused_class();
            case "plugin.orphaned_data" -> Messages.Label_Type_plugin_orphaned_data();
            case "plugin.needs_review" -> Messages.Label_Type_plugin_needs_review();
            default -> type;
        };
    }

    @NonNull
    public static String scanState(@NonNull String name) {
        return switch (name) {
            case "COMPLETED" -> Messages.Label_Scan_COMPLETED();
            case "PARTIAL" -> Messages.Label_Scan_PARTIAL();
            case "CANCELLED" -> Messages.Label_Scan_CANCELLED();
            default -> name;
        };
    }

    @NonNull
    public static String collectorStatus(@NonNull String name) {
        return switch (name) {
            case "COMPLETE" -> Messages.Label_Collector_COMPLETE();
            case "PARTIAL" -> Messages.Label_Collector_PARTIAL();
            case "FAILED" -> Messages.Label_Collector_FAILED();
            case "SKIPPED" -> Messages.Label_Collector_SKIPPED();
            case "CANCELLED" -> Messages.Label_Collector_CANCELLED();
            default -> name;
        };
    }

    /** "2026-11-04 17:26 UTC": no seconds, no fractions. The full ISO value stays in the title/datetime attribute. */
    @NonNull
    public static String shortTime(@NonNull Instant t) {
        return SHORT.format(t);
    }

    /** "5 min 12 sec ago" in Jenkins' own wording, or "just now". */
    @NonNull
    public static String ago(@NonNull Instant t) {
        long millis = Math.max(0, System.currentTimeMillis() - t.toEpochMilli());
        return millis < 10_000 ? Messages.Time_JustNow() : Messages.Time_Ago(hudson.Util.getTimeSpanString(millis));
    }
}
