package io.jenkins.plugins.hygieneauditor.collector.plugins;

import edu.umd.cs.findbugs.annotations.CheckForNull;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamException;
import jenkins.model.Jenkins;

/**
 * Two kinds of finding about plugins.
 *
 * <ul>
 *   <li>{@code plugin.orphaned_data}: configuration that a no-longer-installed plugin wrote. Two independent sources,
 *       which corroborate each other: Jenkins' {@link OldDataMonitor} (unreadable data; it does <em>not</em> report
 *       items inside folders), and our own scan for {@code plugin="name@version"} stamps naming a plugin that is not
 *       installed (works everywhere, but a renamed plugin looks the same).
 *   <li>{@code plugin.needs_review}: an installed, enabled, non-bundled plugin that contains code, that nothing depends
 *       on, and that leaves no <em>observable</em> trace: no stamp, no class named in any configuration, no persisted
 *       global settings file, no step in the latest run of any pipeline job. <b>Always LOW confidence and INFO
 *       severity:</b> a great many plugins legitimately leave none.
 * </ul>
 *
 * As elsewhere, nothing is emitted unless every source was read.
 */
@Extension
public class PluginsCollector extends HygieneCollector {
    public static final String ID = "plugins";
    private static final String SRC_ORPHAN = "old-data-monitor";
    private static final String SRC_STAMP = "plugin-stamps";
    private static final String SRC = "plugin-footprint";
    private static final int MAX_CLASSES = 20_000;
    private static final Pattern MISSING_CLASS = Pattern.compile("CannotResolveClassException:\\s*([\\w.$]+)");
    private static final Pattern CLASS_FILE = Pattern.compile("[A-Za-z0-9_$]+(\\.[A-Za-z0-9_$]+)+");

    /** Maps a class name to the short name of the plugin that owns it, or null. Replaceable in tests. */
    private final Function<String, String> classOwner;

    public PluginsCollector() {
        this(null);
    }

    PluginsCollector(@CheckForNull Function<String, String> classOwner) {
        this.classOwner = classOwner;
    }

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
        /** subject (item full name, or a root file / agent label) to the plugin names stamped in its configuration */
        final Map<String, Set<String>> stampsBySubject = new LinkedHashMap<>();

        final Set<String> stamped = new HashSet<>();
        final Set<String> classNames = new HashSet<>();
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
                Path n = p.getFileName();
                scanFile(p, "(root) " + (n == null ? "?" : n), fp);
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
                    Path n = dir.getFileName();
                    scanFile(dir.resolve("config.xml"), "(agent) " + (n == null ? "?" : n), fp);
                }
            } catch (IOException e) {
                fp.unreadable++;
            }
        }
        for (Item item : Items.allItems2(ACL.SYSTEM2, jenkins, Item.class)) {
            ctx.checkpoint();
            try {
                scanFile(Items.getConfigFile(item).getFile().toPath(), item.getFullName(), fp);
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
        Set<String> viaClass = owners(pm, fp.classNames);

        // ---- verdicts
        orphanedData(ctx, installed, fp);
        needsReview(ctx, pm, fp, viaClass);
        return CollectorResult.complete();
    }

    // ----------------------------------------------------------- orphaned data

    private void orphanedData(ScanContext ctx, Set<String> installed, Footprint fp) {
        Map<String, String> odmExtra = new LinkedHashMap<>();
        OldDataMonitor odm = ExtensionList.lookupSingleton(OldDataMonitor.class);
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
            odmExtra.putIfAbsent(name, extra);
        }
        Map<String, Set<String>> missingBySubject = new LinkedHashMap<>();
        fp.stampsBySubject.forEach((subject, stamps) -> {
            Set<String> missing = new TreeSet<>(stamps);
            missing.removeAll(installed);
            if (!missing.isEmpty()) {
                missingBySubject.put(subject, missing);
            }
        });

        Set<String> subjects = new TreeSet<>(odmExtra.keySet());
        subjects.addAll(missingBySubject.keySet());
        for (String name : subjects) {
            if (name.isBlank() || name.length() > Subject.MAX_NAME) {
                continue;
            }
            String extra = odmExtra.get(name);
            Set<String> missing = missingBySubject.get(name);
            List<Evidence> ev = new ArrayList<>();
            if (extra != null) {
                ev.add(new Evidence(SRC_ORPHAN, "Jenkins reports data it cannot read: " + oneLine(extra), 60));
                if (missing == null) {
                    Matcher m = MISSING_CLASS.matcher(extra);
                    if (m.find()) {
                        ev.add(new Evidence(SRC_ORPHAN, "The class that cannot be found: " + m.group(1), 0));
                    }
                }
            }
            if (missing != null) {
                ev.add(new Evidence(
                        SRC_STAMP,
                        "The stored configuration marks data as written by plugin(s) that are not installed: "
                                + String.join(", ", missing),
                        55));
                ev.add(new Evidence(
                        SRC_STAMP,
                        "A plugin that was renamed leaves the same trace, and so does a plugin removed on purpose after its data was no longer needed",
                        0));
            }
            ev.add(new Evidence(
                    SRC_ORPHAN,
                    "Jenkins' own list covers only data loaded since the last restart and omits items inside folders; the stamps stay in the XML until the item is saved again",
                    0));
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

    // ------------------------------------------------------------- needs review

    private void needsReview(ScanContext ctx, PluginManager pm, Footprint fp, Set<String> viaClass) {
        for (PluginWrapper p : pm.getPlugins()) {
            String name = p.getShortName();
            if (p.isBundled() || !p.isEnabled() || !p.getDependents().isEmpty() || name.equals("hygiene-auditor")) {
                continue; // shipped with Jenkins, already disabled, a library others need, or ourselves
            }
            if (fp.stamped.contains(name)
                    || fp.globalFiles.contains(name)
                    || viaClass.contains(name)
                    || fp.stepCounts.containsKey(name)) {
                continue;
            }
            if (!PluginCode.hasCode(p)) {
                continue; // a suite / meta-plugin: it exists to install other plugins, so "nothing uses it" says
                // nothing
            }
            List<Evidence> ev = new ArrayList<>();
            ev.add(new Evidence(
                    SRC,
                    "No configuration file marks data as written by this plugin or names one of its classes ("
                            + fp.configFiles + " files read), and it has no persisted global settings file",
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

    private void scanFile(Path file, String subject, Footprint fp) {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            try (InputStream in = Files.newInputStream(file)) {
                PluginAttributes.Result r = PluginAttributes.scan(in);
                fp.configFiles++;
                if (!r.pluginStamps().isEmpty()) {
                    fp.stampsBySubject
                            .computeIfAbsent(subject, k -> new TreeSet<>())
                            .addAll(r.pluginStamps());
                    fp.stamped.addAll(r.pluginStamps());
                }
                for (String c : r.classNames()) {
                    if (fp.classNames.size() < MAX_CLASSES) {
                        fp.classNames.add(c);
                    }
                }
            }
        } catch (IOException | XMLStreamException e) {
            fp.unreadable++;
        }
    }

    /** The plugins that own the classes configuration mentions. */
    private Set<String> owners(PluginManager pm, Set<String> classNames) {
        Function<String, String> resolve = classOwner != null ? classOwner : name -> ownerOf(pm, name);
        Set<String> out = new HashSet<>();
        for (String c : classNames) {
            String owner = resolve.apply(c);
            if (owner != null) {
                out.add(owner);
            }
        }
        return out;
    }

    /** Loads the class without initialising it and asks Jenkins which plugin it came from; null for core or unknown. */
    @CheckForNull
    private static String ownerOf(PluginManager pm, String className) {
        try {
            PluginWrapper pw = pm.whichPlugin(pm.uberClassLoader.loadClass(className));
            return pw == null ? null : pw.getShortName();
        } catch (ClassNotFoundException | LinkageError e) {
            return null; // belongs to no installed plugin (or to core)
        }
    }

    /**
     * The class a root settings file is named after ({@code hudson.plugins.git.GitTool.xml} → {@code
     * hudson.plugins.git.GitTool}), or null for files that are not class-named ({@code config.xml},
     * {@code nodeMonitors.xml}, ...).
     */
    @CheckForNull
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

    private static void classFileOwner(PluginManager pm, Path file, Set<String> into) {
        String className = classNameOf(file);
        if (className != null) {
            String owner = ownerOf(pm, className);
            if (owner != null) {
                into.add(owner);
            }
        }
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\p{Cntrl}+", " ").trim();
    }
}
