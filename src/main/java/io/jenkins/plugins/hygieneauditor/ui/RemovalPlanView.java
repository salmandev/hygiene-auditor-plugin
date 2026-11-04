package io.jenkins.plugins.hygieneauditor.ui;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.export.RemovalPlan;
import jenkins.security.stapler.StaplerAccessibleType;

/** A generated removal plan, shown in a page with Copy and Download. Read-only: nothing here changes anything. */
@StaplerAccessibleType
public final class RemovalPlanView {
    private final RemovalPlan.Format format;
    private final int count;
    private final String text;
    private final String downloadUrl;

    public RemovalPlanView(
            @NonNull RemovalPlan.Format format, int count, @NonNull String text, @NonNull String downloadUrl) {
        this.format = format;
        this.count = count;
        this.text = text;
        this.downloadUrl = downloadUrl;
    }

    /** {@code groovy}, {@code cli} or {@code checklist}; used by the page to pick the instructions. */
    public String getFormatName() {
        return format.name().toLowerCase(java.util.Locale.ROOT);
    }

    public String getFileName() {
        return "hygiene-removal-plan." + format.extension();
    }

    public int getCount() {
        return count;
    }

    public String getText() {
        return text;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }
}
