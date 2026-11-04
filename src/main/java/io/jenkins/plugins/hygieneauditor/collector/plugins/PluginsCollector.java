package io.jenkins.plugins.hygieneauditor.collector.plugins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.PluginManager;
import hudson.PluginWrapper;
import hudson.diagnosis.OldDataMonitor;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.Saveable;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.hygieneauditor.Messages;
import io.jenkins.plugins.hygieneauditor.collector.BudgetExceededException;
import io.jenkins.plugins.hygieneauditor.collector.CollectorResult;
import io.jenkins.plugins.hygieneauditor.collector.ConfidenceScorer;
import io.jenkins.plugins.hygieneauditor.collector.HygieneCollector;
import io.jenkins.plugins.hygieneauditor.collector.ScanContext;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamException;
import jenkins.model.Jenkins;

/**
 * Two kinds of finding about plugins.
 *
 * <ul>
 *   <li>{@code plugin.orphaned_data}: Jenkins' own {@link OldDataMonitor} reports stored data it cannot read (typically
 *       written by a plugin that is no longer installed). We add the name of the missing plugin when the item's
 *       configuration still says which plugin wrote it.
 *   <li>{@code plugin.needs_review}: an installed, enabled, non-bundled plugin that nothing depends on and that leaves
 *       no <em>observable</em> trace: no {@code plugin="…"} stamp in any configuration, no persisted global
 *       configuration file, and no step in the latest run of any pipeline job. <b>Always LOW confidence and INFO
 *       severity:</b> a great many plugins (themes, UI helpers, build-time tools) legitimately leave none.
 * </ul>
 *
 * As elsewhere, nothing is emitted unless every source was read.
 */
@Extension
public class PluginsCollector extends HygieneCollector {
    public static final String ID = "plugins";
    private static final String SRC_ORPHAN = "old-data-monitor";
    private static final String SRC = "plugin-footprint";
    private static final Pattern MISSING_CLASS = Pattern.compile("CannotResolveClassException:\\s*([\\w.$]+)");
    private static final Pattern CLASS_FILE = Pattern.compile("[A-Za-z0-9_$]+(\\.[A-Za-z0-9_$]+)+");

    @NonNull
    @Override
    public String getId() {
        return ID;
    }

    @NonNull
    @Override
    public Category getCategory() {
        return Category.PLUGINS;
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return Messages.PluginsCollector_DisplayName();
    }

    @NonNull
    @Override
    public CollectorResult collect(@NonNull ScanContext ctx) throws InterruptedException {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return run(ctx);
        }
    }

    /** What the sources showed. */
    private static final class Footprint {
        final Set<String> configStamped = new HashSet<>();
        final Set<String> globalFiles = new HashSet<>();
        final Map<String, Integer> stepCounts = new HashMap<>();
        int configFiles;
        int runsInspected;
        int unreadable;
    }

    private CollectorResult run(ScanContext ctx) throws InterruptedException {
        Jenkins jenkins = Jenkins.get();
        PluginManager pm = jenkins.getPluginManager();
        Set<String> installed = new HashSet<>();
        pm.getPlugins().forEach(p -> installed.add(p.getShortName()));

        // ---- footprint (reads everything before anything is emitted)
        Footprint fp = new Footprint();
        boolean pipelines = jenkins.getPlugin("workflow-cps") != null;
        Path root = jenkins.getRootDir().toPath();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, "*.xml")) {
            for (Path p : ds) {
                ctx.checkpoint();
                stamps(p, fp.configStamped, fp);
                classFileOwner(pm, p, fp.globalFiles);
            }
        } catch (IOException e) {
            fp.unreadable++;
        }
        Path nodes = root.resolve("nodes");
        if (Files.isDirectory(nodes)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(nodes)) {
                for (Path dir : ds) {
                    ctx.checkpoint();
                    stamps(dir.resolve("config.xml"), fp.configStamped, fp);
                }
            } catch (IOException e) {
                fp.unreadable++;
            }
        }
        for (Item item : Items.allItems2(ACL.SYSTEM2, jenkins, Item.class)) {
            ctx.checkpoint();
            try {
                stamps(Items.getConfigFile(item).getFile().toPath(), fp.configStamped, fp);
                if (pipelines && item instanceof Job<?, ?> job) {
                    Set<String> used = PipelineStepPlugins.of(job);
                    if (!used.isEmpty()) {
                        fp.runsInspected++;
                        used.forEach(n -> fp.stepCounts.merge(n, 1, Integer::sum));
                    }
                }
            } catch (BudgetExceededException e) {
                throw e;
            } catch (RuntimeException e) {
                fp.unreadable++;
            }
        }

        // ---- verdicts
        orphanedData(ctx, installed);
        needsReview(ctx, pm, fp);
        return CollectorResult.complete();
    }

    // ----------------------------------------------------------- orphaned data

    private void orphanedData(ScanContext ctx, Set<String> installed) {
        OldDataMonitor odm = ExtensionList.lookupSingleton(OldDataMonitor.class);
        Set<String> done = new HashSet<>();
        for (Map.Entry<Saveable, OldDataMonitor.VersionRange> e : odm.getData().entrySet()) {
            String extra = e.getValue().extra;
            if (extra == null || extra.isBlank()) {
                continue; // data written by a NEWER version: not orphaned, and not ours to report
            }
            Saveable s = e.getKey();
            String name = s instanceof Item i
                    ? i.getFullName()
                    : s instanceof Run<?, ?> r
                            ? r.getExternalizableId()
                            : s.getClass().getName();
            if (name.isBlank() || name.length() > Subject.MAX_NAME || !done.add(name)) {
                continue;
            }
            List<Evidence> ev = new ArrayList<>();
            ev.add(new Evidence(SRC_ORPHAN, "Jenkins reports data it cannot read: " + oneLine(extra), 60));
            Set<String> missing = missingPlugins(s, installed, extra);
            if (!missing.isEmpty()) {
                ev.add(new Evidence(
                        SRC_ORPHAN, "Written by plugin(s) that are not installed: " + String.join(", ", missing), 0));
            }
            ev.add(new Evidence(
                    SRC_ORPHAN,
                    "The same symptom appears when a plugin upgrade removed a class; Jenkins lists only data loaded since the last restart",
                    0));
            ev.add(new Evidence(SRC_ORPHAN, "The unreadable XML stays on disk until the item is saved again", 0));
            ctx.emit(new FindingCandidate(
                    "plugin.orphaned_data",
                    Category.PLUGINS,
                    Subject.of("plugin_data", name, name),
                    Severity.LOW,
                    ConfidenceScorer.score(ev, 0),
                    ev,
                    Messages.PluginsCollector_OrphanAction()));
        }
    }

    private static Set<String> missingPlugins(Saveable s, Set<String> installed, String extra) {
        Set<String> names = new TreeSet<>();
        if (s instanceof Item item) {
            try (InputStream in =
                    Files.newInputStream(Items.getConfigFile(item).getFile().toPath())) {
                Set<String> stamped = new HashSet<>();
                PluginAttributes.scan(in, stamped);
                stamped.stream().filter(n -> !installed.contains(n)).forEach(names::add);
            } catch (IOException | XMLStreamException | RuntimeException e) {
                // best effort: the class name below still identifies the cause
            }
        }
        if (names.isEmpty()) {
            Matcher m = MISSING_CLASS.matcher(extra);
            if (m.find()) {
                names.add("class " + m.group(1));
            }
        }
        return names;
    }

    // ------------------------------------------------------------- needs review

    private void needsReview(ScanContext ctx, PluginManager pm, Footprint fp) {
        for (PluginWrapper p : pm.getPlugins()) {
            String name = p.getShortName();
            if (p.isBundled() || !p.isEnabled() || !p.getDependents().isEmpty() || name.equals("hygiene-auditor")) {
                continue; // shipped with Jenkins, already disabled, a library others need, or ourselves
            }
            if (fp.configStamped.contains(name) || fp.globalFiles.contains(name) || fp.stepCounts.containsKey(name)) {
                continue;
            }
            List<Evidence> ev = new ArrayList<>();
            ev.add(new Evidence(
                    SRC,
                    "No configuration file marks data as written by this plugin (" + fp.configFiles
                            + " files read), and it has no persisted global configuration file",
                    30));
            ev.add(new Evidence(
                    SRC,
                    "None of the steps in the latest run of " + fp.runsInspected + " pipeline job(s) come from it",
                    0));
            ev.add(new Evidence(SRC, "No other installed plugin depends on it", 0));
            ev.add(new Evidence(
                    SRC,
                    "Many plugins leave no trace in configuration (themes, UI helpers, build-time tools, listeners): this is a question for whoever installed it, not a verdict",
                    0));
            if (fp.unreadable > 0) {
                ev.add(new Evidence(SRC, fp.unreadable + " file(s) could not be read; it may be used there", 0));
            }
            ev.add(new Evidence(
                    SRC,
                    "Not checked: Jenkinsfile text, older runs, extension points that need no configuration, users' settings",
                    0));
            ctx.emit(new FindingCandidate(
                    "plugin.needs_review",
                    Category.PLUGINS,
                    Subject.of("plugin", name, p.getDisplayName() + " (" + name + " " + p.getVersion() + ")"),
                    Severity.INFO,
                    ConfidenceScorer.score(ev, 0), // 30 < 40: always LOW, by construction
                    ev,
                    Messages.PluginsCollector_ReviewAction()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private void stamps(Path file, Set<String> into, Footprint fp) {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            try (InputStream in = Files.newInputStream(file)) {
                PluginAttributes.scan(in, into);
                fp.configFiles++;
            }
        } catch (IOException | XMLStreamException e) {
            fp.unreadable++;
        }
    }

    /**
     * The class a root settings file is named after ({@code hudson.plugins.git.GitTool.xml} → {@code
     * hudson.plugins.git.GitTool}), or null for files that are not class-named ({@code config.xml},
     * {@code nodeMonitors.xml}, ...).
     */
    static String classNameOf(Path file) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return null;
        }
        String n = fileName.toString();
        if (!n.endsWith(".xml")) {
            return null;
        }
        String className = n.substring(0, n.length() - 4);
        return CLASS_FILE.matcher(className).matches() ? className : null;
    }

    /**
     * A root file named after a class means that class's plugin saved settings. The class is loaded without being
     * initialised. Note: the attribution relies on the class coming from the plugin's own class loader, which is true
     * in a running Jenkins but not when the plugin's jar is also on a test classpath.
     */
    private static void classFileOwner(PluginManager pm, Path file, Set<String> into) {
        String className = classNameOf(file);
        if (className == null) {
            return;
        }
        try {
            PluginWrapper pw = pm.whichPlugin(pm.uberClassLoader.loadClass(className));
            if (pw != null) {
                into.add(pw.getShortName());
            }
        } catch (ClassNotFoundException | LinkageError e) {
            // the class belongs to no installed plugin
        }
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\p{Cntrl}+", " ").trim();
    }
}
