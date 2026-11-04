package io.jenkins.plugins.hygieneauditor.rest;

import hudson.model.Api;
import io.jenkins.plugins.hygieneauditor.HygieneAuditorPermissions;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import java.io.IOException;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/** The filtered, paged findings list served at {@code findings/api/json}. */
@ExportedBean(defaultVisibility = 999)
public class FindingsBean {
    private final FindingQuery query;
    private final FindingService.Page page;

    private FindingsBean(FindingQuery query, FindingService.Page page) {
        this.query = query;
        this.page = page;
    }

    /** Stapler entry point: reads the filter from the current request. */
    public static Api api() throws IOException {
        Jenkins.get().checkPermission(HygieneAuditorPermissions.VIEW);
        return api(FindingQuery.fromRequest(Stapler.getCurrentRequest2()));
    }

    /** For callers that have already checked permission and may have adjusted the query (per-folder page). */
    public static Api api(FindingQuery q) throws IOException {
        return new Api(new FindingsBean(q, FindingService.query(q)));
    }

    @Exported
    public int getTotal() {
        return page.total();
    }

    @Exported
    public int getOffset() {
        return query.offset();
    }

    @Exported
    public int getLimit() {
        return query.limit();
    }

    @Exported
    public List<FindingBean> getFindings() {
        List<Finding> items = page.items();
        return items.stream().map(FindingBean::new).toList();
    }
}
