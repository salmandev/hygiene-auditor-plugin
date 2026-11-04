package io.jenkins.plugins.hygieneauditor;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor.FormException;
import hudson.scheduler.CronTab;
import hudson.scheduler.Hash;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.hygieneauditor.collector.HygieneCollector;
import io.jenkins.plugins.hygieneauditor.collector.agents.AgentsSettings;
import io.jenkins.plugins.hygieneauditor.collector.credentials.CredentialsSettings;
import io.jenkins.plugins.hygieneauditor.collector.jobs.JobsSettings;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.notify.WebhookNotifier;
import io.jenkins.plugins.hygieneauditor.policy.IgnorePolicy;
import io.jenkins.plugins.hygieneauditor.policy.IgnoreRule;
import io.jenkins.plugins.hygieneauditor.store.RetentionPolicy;
import io.jenkins.plugins.hygieneauditor.ui.Labels;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.GlobalConfiguration;
import jenkins.model.GlobalConfigurationCategory;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * Global settings, persisted as XML and configurable through JCasC under {@code unclassified: hygieneAuditor:}.
 * Every property has a safe default, so an empty section is valid.
 */
@Extension
@Symbol("hygieneAuditor")
public final class HygieneAuditorConfiguration extends GlobalConfiguration {

    private String scanCron = "";
    private List<String> disabledCollectors = new ArrayList<>();

    private int staleJobDays = 365;
    private int neverBuiltGraceDays = 30;
    private int disabledJobDays = 180;
    private int offlineAgentDays = 30;
    private int credentialStaleUseDays = 180;

    private int maxFindings = 50_000;
    private int resolvedRetentionDays = 30;

    private List<IgnoreRule> ignoreRules = new ArrayList<>();

    private Severity notifyMinSeverity = Severity.HIGH;
    private String webhookUrl = "";
    private Secret webhookSecret;
    private String emailRecipients = "";

    public HygieneAuditorConfiguration() {
        load();
    }

    @NonNull
    public static HygieneAuditorConfiguration get() {
        return GlobalConfiguration.all().getInstance(HygieneAuditorConfiguration.class);
    }

    @NonNull
    @Override
    public GlobalConfigurationCategory getCategory() {
        return GlobalConfigurationCategory.get(GlobalConfigurationCategory.Unclassified.class);
    }

    // ---- schedule / collectors

    @NonNull
    public synchronized String getScanCron() {
        return scanCron == null ? "" : scanCron;
    }

    @DataBoundSetter
    public synchronized void setScanCron(String value) {
        String v = value == null ? "" : value.trim();
        String problem = validateCron(v);
        if (problem != null) {
            throw new IllegalArgumentException(Messages.HygieneAuditorConfiguration_InvalidCron(problem));
        }
        this.scanCron = v;
        save();
    }

    @NonNull
    public synchronized List<String> getDisabledCollectors() {
        return disabledCollectors == null ? List.of() : List.copyOf(disabledCollectors);
    }

    @DataBoundSetter
    public synchronized void setDisabledCollectors(List<String> ids) {
        this.disabledCollectors = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
        save();
    }

    public boolean isCollectorEnabled(@NonNull String id) {
        return !getDisabledCollectors().contains(id);
    }

    // ---- thresholds

    public synchronized int getStaleJobDays() {
        return staleJobDays;
    }

    @DataBoundSetter
    public synchronized void setStaleJobDays(int days) {
        this.staleJobDays = positive("staleJobDays", days);
        save();
    }

    public synchronized int getNeverBuiltGraceDays() {
        return neverBuiltGraceDays;
    }

    @DataBoundSetter
    public synchronized void setNeverBuiltGraceDays(int days) {
        this.neverBuiltGraceDays = nonNegative("neverBuiltGraceDays", days);
        save();
    }

    public synchronized int getDisabledJobDays() {
        return disabledJobDays;
    }

    @DataBoundSetter
    public synchronized void setDisabledJobDays(int days) {
        this.disabledJobDays = positive("disabledJobDays", days);
        save();
    }

    public synchronized int getOfflineAgentDays() {
        return offlineAgentDays;
    }

    @DataBoundSetter
    public synchronized void setOfflineAgentDays(int days) {
        this.offlineAgentDays = positive("offlineAgentDays", days);
        save();
    }

    public synchronized int getCredentialStaleUseDays() {
        return credentialStaleUseDays;
    }

    @DataBoundSetter
    public synchronized void setCredentialStaleUseDays(int days) {
        this.credentialStaleUseDays = positive("credentialStaleUseDays", days);
        save();
    }

    // ---- retention

    public synchronized int getMaxFindings() {
        return maxFindings;
    }

    @DataBoundSetter
    public synchronized void setMaxFindings(int max) {
        this.maxFindings = positive("maxFindings", max);
        save();
    }

    public synchronized int getResolvedRetentionDays() {
        return resolvedRetentionDays;
    }

    @DataBoundSetter
    public synchronized void setResolvedRetentionDays(int days) {
        this.resolvedRetentionDays = nonNegative("resolvedRetentionDays", days);
        save();
    }

    // ---- ignore policy

    @NonNull
    public synchronized List<IgnoreRule> getIgnoreRules() {
        return ignoreRules == null ? List.of() : List.copyOf(ignoreRules);
    }

    @DataBoundSetter
    public synchronized void setIgnoreRules(List<IgnoreRule> rules) {
        List<IgnoreRule> copy = rules == null ? new ArrayList<>() : new ArrayList<>(rules);
        copy.forEach(IgnoreRule::validate);
        this.ignoreRules = copy;
        save();
    }

    // ---- notifications

    @NonNull
    public synchronized Severity getNotifyMinSeverity() {
        return notifyMinSeverity == null ? Severity.HIGH : notifyMinSeverity;
    }

    @DataBoundSetter
    public synchronized void setNotifyMinSeverity(Severity s) {
        this.notifyMinSeverity = s == null ? Severity.HIGH : s;
        save();
    }

    @NonNull
    public synchronized String getWebhookUrl() {
        return webhookUrl == null ? "" : webhookUrl;
    }

    /** Only absolute http(s) URLs without embedded credentials are accepted. */
    @DataBoundSetter
    public synchronized void setWebhookUrl(String url) {
        String v = url == null ? "" : url.trim();
        if (!v.isEmpty()) {
            WebhookNotifier.parse(v); // throws IllegalArgumentException with a safe message
        }
        this.webhookUrl = v;
        save();
    }

    @CheckForNull
    public synchronized Secret getWebhookSecret() {
        return webhookSecret;
    }

    @DataBoundSetter
    public synchronized void setWebhookSecret(Secret secret) {
        this.webhookSecret = secret;
        save();
    }

    @NonNull
    public synchronized String getEmailRecipients() {
        return emailRecipients == null ? "" : emailRecipients;
    }

    /** Comma, semicolon or space separated. Light check only here; the mail library validates strictly on send. */
    @DataBoundSetter
    public synchronized void setEmailRecipients(String recipients) {
        String v = recipients == null ? "" : recipients.trim();
        for (String part : v.split("[,;\\s]+")) {
            if (!part.isEmpty() && !part.matches("[^\\s@<>,;\"]+@[^\\s@<>,;\"]+")) {
                throw new IllegalArgumentException("Not an email address: " + part.replaceAll("\\p{Cntrl}", "?"));
            }
        }
        this.emailRecipients = v;
        save();
    }

    @POST
    public FormValidation doCheckWebhookUrl(@QueryParameter String value) {
        Jenkins.get().checkPermission(HygieneAuditorPermissions.CONFIGURE);
        if (value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        try {
            URL u = WebhookNotifier.parse(value.trim());
            return u.getProtocol().equalsIgnoreCase("http")
                    ? FormValidation.warning(Messages.HygieneAuditorConfiguration_WebhookPlainHttp())
                    : FormValidation.ok();
        } catch (IllegalArgumentException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    @POST
    public ListBoxModel doFillNotifyMinSeverityItems() {
        ListBoxModel m = new ListBoxModel();
        if (!Jenkins.get().hasPermission(Jenkins.MANAGE)) {
            return m;
        }
        for (Severity s : Severity.values()) {
            m.add(Labels.severity(s), s.name());
        }
        return m;
    }

    // ---- form support

    /** For the settings form: every installed collector. */
    @NonNull
    public List<HygieneCollector> getCollectors() {
        return HygieneCollector.all().stream()
                .sorted(java.util.Comparator.comparing(HygieneCollector::getId))
                .toList();
    }

    /**
     * Form field name for a collector's checkbox. Deliberately free of dots: Jenkins strips everything before the
     * last dot of a field name when it builds the submitted JSON.
     */
    @NonNull
    public static String formKey(@NonNull String collectorId) {
        return "collector_" + collectorId.replaceAll("[^A-Za-z0-9]", "_");
    }

    /**
     * The form submits one {@code collector_<id>} checkbox per collector; those are turned into the
     * {@code disabledCollectors} list here so JCasC never sees a second representation of the same setting.
     */
    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        List<String> disabled = new ArrayList<>();
        boolean sawCheckboxes = false;
        for (HygieneCollector c : HygieneCollector.all()) {
            String key = formKey(c.getId());
            sawCheckboxes |= json.has(key);
            if (!json.optBoolean(key, false)) {
                disabled.add(c.getId());
            }
        }
        try {
            if (sawCheckboxes) {
                setDisabledCollectors(disabled);
            }
            // The list is rebuilt from the form, so a form without rules clears them.
            if (!json.has("ignoreRules")) {
                setIgnoreRules(List.of());
            }
            req.bindJSON(this, json);
        } catch (IllegalArgumentException e) {
            throw new FormException(e.getMessage(), "hygieneAuditor");
        }
        return true;
    }

    // ---- derived, used by the engine

    @NonNull
    public synchronized JobsSettings jobsSettings() {
        return new JobsSettings(staleJobDays, neverBuiltGraceDays, disabledJobDays);
    }

    @NonNull
    public synchronized AgentsSettings agentsSettings() {
        return new AgentsSettings(offlineAgentDays);
    }

    @NonNull
    public synchronized CredentialsSettings credentialsSettings() {
        return new CredentialsSettings(
                credentialStaleUseDays, CredentialsSettings.defaults().maxFileBytes());
    }

    @NonNull
    public synchronized RetentionPolicy retentionPolicy() {
        return new RetentionPolicy(maxFindings, resolvedRetentionDays);
    }

    @NonNull
    public IgnorePolicy ignorePolicy() {
        return new IgnorePolicy(getIgnoreRules());
    }

    // ---- validation

    /** Returns null if every cron line parses, else the parse error. Sanity warnings are not errors, see below. */
    public static String validateCron(String spec) {
        return inspectCron(spec, false);
    }

    /** Returns Jenkins's advisory (for example "did you mean H?") for a valid spec, or null. */
    public static String cronWarning(String spec) {
        return inspectCron(spec, true);
    }

    private static String inspectCron(String spec, boolean sanity) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        int line = 0;
        for (String l : spec.split("\\R")) {
            line++;
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            try {
                CronTab tab = new CronTab(t, line, Hash.from("validate"));
                if (sanity) {
                    String advice = tab.checkSanity();
                    if (advice != null) {
                        return advice;
                    }
                }
            } catch (RuntimeException e) {
                if (!sanity) {
                    return e.getMessage() == null ? "line " + line : e.getMessage();
                }
            }
        }
        return null;
    }

    private static int positive(String name, int v) {
        if (v < 1) {
            throw new IllegalArgumentException(name + " must be at least 1");
        }
        return v;
    }

    private static int nonNegative(String name, int v) {
        if (v < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return v;
    }

    @POST
    public FormValidation doCheckScanCron(@QueryParameter String value) {
        Jenkins.get().checkPermission(HygieneAuditorPermissions.CONFIGURE);
        String problem = validateCron(value);
        if (problem != null) {
            return FormValidation.error(Messages.HygieneAuditorConfiguration_InvalidCron(problem));
        }
        String advice = cronWarning(value);
        return advice == null ? FormValidation.ok() : FormValidation.warning(advice);
    }
}
