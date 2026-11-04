package io.jenkins.plugins.hygieneauditor.collector.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.hygieneauditor.Messages;
import io.jenkins.plugins.hygieneauditor.collector.BudgetExceededException;
import io.jenkins.plugins.hygieneauditor.collector.CollectorResult;
import io.jenkins.plugins.hygieneauditor.collector.ConfidenceScorer;
import io.jenkins.plugins.hygieneauditor.collector.HygieneCollector;
import io.jenkins.plugins.hygieneauditor.collector.ScanContext;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.stream.XMLStreamException;
import jenkins.model.Jenkins;

/**
 * Finds credentials that nothing appears to use. Finding type: {@code credential.unused}.
 *
 * <p>Evidence sources: (a) fingerprint facets (last recorded use), (b) a static scan of item and node
 * configurations, global XML configuration, the pipeline source of each job's latest run, and JCasC files for
 * credentialsId references, (c) detection of dynamic references that cannot be resolved, which lower confidence.
 *
 * <p><b>Safety rule:</b> findings are emitted only after every source has been read. If the scan is cut short (budget,
 * cancellation) nothing is emitted, because an incomplete scan cannot support the claim "unused".
 */
@Extension
public class CredentialsCollector extends HygieneCollector {
    public static final String ID = "credentials";
    private static final String SRC_STATIC = "static-scan";
    private static final String SRC_FP = "fingerprint";
    private static final int MAX_CASC_FILES = 500;

    /** Null means: read the global configuration at scan time. */
    private final CredentialsSettings fixedSettings;

    public CredentialsCollector() {
        this.fixedSettings = null;
    }

    CredentialsCollector(CredentialsSettings settings) {
        this.fixedSettings = settings;
    }

    private CredentialsSettings settings() {
        return fixedSettings != null
                ? fixedSettings
                : io.jenkins.plugins.hygieneauditor.HygieneAuditorConfiguration.get()
                        .credentialsSettings();
    }

    @NonNull
    @Override
    public String getId() {
        return ID;
    }

    @NonNull
    @Override
    public Category getCategory() {
        return Category.CREDENTIALS;
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return Messages.CredentialsCollector_DisplayName();
    }

    @Override
    public boolean isApplicable() {
        return Jenkins.get().getPlugin("credentials") != null;
    }

    @NonNull
    @Override
    public CollectorResult collect(@NonNull ScanContext ctx) throws InterruptedException {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return run(ctx);
        }
    }

    private CollectorResult run(ScanContext ctx) throws InterruptedException {
        long cutoff = ctx.clock()
                .instant()
                .minus(Duration.ofDays(settings().staleUseDays()))
                .toEpochMilli();
        CredentialInventory.Result inv = CredentialInventory.enumerate(ctx, cutoff);
        List<CredentialRecord> creds = inv.credentials();
        Set<String> ids = creds.stream().map(CredentialRecord::id).collect(Collectors.toSet());
        ReferenceIndex idx = new ReferenceIndex(ids);
        if (!ids.isEmpty()) {
            scanSources(ctx, idx); // may throw BudgetExceededException: then we emit nothing at all
        }
        Instant now = ctx.clock().instant();
        for (CredentialRecord r : creds) {
            if (!idx.isReferenced(r.id())) {
                examine(ctx, r, idx, now);
            }
        }
        return inv.unreadableStores() == 0
                ? CollectorResult.complete()
                : CollectorResult.partial(inv.unreadableStores() + " credential store(s) could not be read");
    }

    private void examine(ScanContext ctx, CredentialRecord r, ReferenceIndex idx, Instant now) {
        List<Evidence> ev = new ArrayList<>();
        int caveats = 0;

        ev.add(new Evidence(
                SRC_STATIC,
                "No reference to this id in " + idx.configFiles() + " configuration files, " + idx.scripts()
                        + " pipeline scripts and " + idx.cascFiles() + " JCasC files",
                45));

        if (r.tracking()) {
            if (r.lastUsed() != null) {
                long days = Duration.between(r.lastUsed(), now).toDays();
                if (days < settings().staleUseDays()) {
                    return; // fingerprints show recent use: whatever the static scan missed, it is in use
                }
                ev.add(new Evidence(SRC_FP, "Fingerprint records its last use " + days + " days ago", 30));
            } else {
                ev.add(new Evidence(
                        SRC_FP,
                        "Fingerprint tracking is enabled and records no use (tracking may have been enabled recently)",
                        20));
            }
        } else {
            ev.add(new Evidence(SRC_FP, "Fingerprint tracking is disabled or unreadable: no usage history", 0));
        }

        int dynamic = idx.dynamicInScope(r.ownerPath());
        if (dynamic > 0) {
            caveats++;
            ev.add(new Evidence(
                    SRC_STATIC,
                    dynamic + " dynamic credential reference(s) in scope could not be resolved; this credential"
                            + " might be selected at runtime",
                    0));
        }
        if (idx.unscanned() > 0) {
            caveats++;
            ev.add(new Evidence(
                    SRC_STATIC,
                    idx.unscanned() + " source(s) could not be read, were too large, or are remote and were skipped",
                    0));
        }
        ev.add(new Evidence(
                SRC_STATIC,
                "Not scanned: shared-library sources, user-scoped credentials, external systems that use this id",
                0));

        Confidence confidence = ConfidenceScorer.score(ev, caveats);
        ctx.emit(new FindingCandidate(
                "credential.unused",
                Category.CREDENTIALS,
                Subject.of("credential", r.subjectFullName(), r.displayName()),
                confidence == Confidence.HIGH ? Severity.HIGH : Severity.MEDIUM,
                confidence,
                ev,
                Messages.CredentialsCollector_SuggestedAction()));
    }

    // ------------------------------------------------------------ sources

    private void scanSources(ScanContext ctx, ReferenceIndex idx) throws InterruptedException {
        Jenkins jenkins = Jenkins.get();
        Path root = jenkins.getRootDir().toPath();

        // Global XML configuration (clouds, SCM servers, ...). Credential stores themselves are excluded.
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, "*.xml")) {
            for (Path p : ds) {
                ctx.checkpoint();
                String name = fileName(p);
                if (!name.equals("credentials.xml") && !name.startsWith("com.cloudbees.plugins.credentials.")) {
                    xml(p, "", idx);
                }
            }
        } catch (IOException e) {
            idx.countUnscanned();
        }
        // Agent definitions (SSH launchers reference credentials).
        Path nodes = root.resolve("nodes");
        if (Files.isDirectory(nodes)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(nodes)) {
                for (Path dir : ds) {
                    ctx.checkpoint();
                    Path cfg = dir.resolve("config.xml");
                    if (Files.isRegularFile(cfg)) {
                        xml(cfg, "", idx);
                    }
                }
            } catch (IOException e) {
                idx.countUnscanned();
            }
        }
        // Every folder and job configuration, plus the latest pipeline source of each job.
        boolean pipelines = jenkins.getPlugin("workflow-cps") != null;
        for (Item item : Items.allItems2(ACL.SYSTEM2, jenkins, Item.class)) {
            ctx.checkpoint();
            try {
                xml(Items.getConfigFile(item).getFile().toPath(), item.getFullName(), idx);
                if (pipelines && item instanceof Job<?, ?> job) {
                    for (String script : PipelineScripts.lastRunScripts(job)) {
                        ReferenceScanner.scanScript(script, item.getFullName(), idx);
                        idx.countScript();
                    }
                }
            } catch (BudgetExceededException e) {
                throw e;
            } catch (RuntimeException e) {
                idx.countUnscanned();
            }
        }
        scanCasc(ctx, idx);
    }

    private static String fileName(Path p) {
        Path n = p.getFileName();
        return n == null ? "" : n.toString();
    }

    private void xml(Path file, String location, ReferenceIndex idx) {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            if (Files.size(file) > settings().maxFileBytes()) {
                idx.countUnscanned();
                return;
            }
            try (InputStream in = Files.newInputStream(file)) {
                ReferenceScanner.scanXml(in, location, idx);
                idx.countConfigFile();
            }
        } catch (IOException | XMLStreamException e) {
            idx.countUnscanned(); // not well-formed (or unreadable): says nothing about the credential, so lower
            // confidence
        }
    }

    /** JCasC sources from CASC_JENKINS_CONFIG / casc.jenkins.config: local files and directories only. */
    private void scanCasc(ScanContext ctx, ReferenceIndex idx) throws InterruptedException {
        String spec = System.getProperty("casc.jenkins.config", System.getenv("CASC_JENKINS_CONFIG"));
        if (spec == null || spec.isBlank()) {
            return;
        }
        Set<Path> files = new LinkedHashSet<>();
        for (String part : spec.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
                idx.countUnscanned(); // remote source: cannot be read safely here
                continue;
            }
            Path p = Path.of(s);
            if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p, 4)) {
                    walk.filter(Files::isRegularFile)
                            .filter(f -> fileName(f).matches("(?i).*\\.(ya?ml|json)"))
                            .limit(MAX_CASC_FILES)
                            .forEach(files::add);
                } catch (IOException e) {
                    idx.countUnscanned();
                }
            } else if (Files.isRegularFile(p)) {
                files.add(p);
            } else {
                idx.countUnscanned();
            }
        }
        for (Path f : files) {
            ctx.checkpoint();
            try {
                if (Files.size(f) > settings().maxFileBytes()) {
                    idx.countUnscanned();
                    continue;
                }
                ReferenceScanner.scanYaml(Files.readString(f, StandardCharsets.UTF_8), idx);
                idx.countCascFile();
            } catch (IOException e) {
                idx.countUnscanned();
            }
        }
    }
}
