package io.jenkins.plugins.hygieneauditor.rest;

import io.jenkins.plugins.hygieneauditor.model.Evidence;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean(defaultVisibility = 999)
public class EvidenceBean {
    private final Evidence e;

    public EvidenceBean(Evidence e) {
        this.e = e;
    }

    @Exported
    public String getSource() {
        return e.source();
    }

    @Exported
    public String getText() {
        return e.text();
    }

    @Exported
    public int getWeight() {
        return e.weight();
    }
}
