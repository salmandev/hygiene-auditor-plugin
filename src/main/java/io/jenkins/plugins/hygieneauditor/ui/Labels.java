package io.jenkins.plugins.hygieneauditor.ui;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.Messages;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Status;

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
}
