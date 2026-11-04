package io.jenkins.plugins.hygieneauditor.collector.libraries;

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
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import jenkins.model.Jenkins;

/**
 * Finds shared-library content that looks unused. Finding types: {@code library.unreferenced} (a configured library no
 * recent run loaded and no script names), {@code library.unused_variable} (a global variable under {@code vars/}) and
 * {@code library.unused_class} (a class under {@code src/}).
 *
 * <p>Evidence (see {@code docs/LIBRARIES-FEASIBILITY.md}): the libraries each pipeline job's latest run actually loaded
 * ({@code LibrariesAction}), the pipeline source that ran, and the library files that run checked out. Runtime evidence
 * for an individual variable or class does not exist in the flow graph, so those verdicts are static, over the code
 * that really ran.
 *
 * <p>As with credentials, nothing is emitted unless every source was read: a cut-short scan makes no claim.
 */
@Extension
public class SharedLibrariesCollector extends HygieneCollector {
    public static final String ID = "libraries";
    private static final String SRC = "library-scan";
    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final int MAX_FILES_PER_LIBRARY = 5_000;
    /** Runs inspected at which coverage counts as broad. */
    private static final int BROAD = 10;

    @NonNull
    @Override
    public String getId() {
        return ID;
    }

    @NonNull
    @Override
    public Category getCategory() {
        return Category.SHARED_LIBRARIES;
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return Messages.SharedLibrariesCollector_DisplayName();
    }

    @Override
    public boolean isApplicable() {
        Jenkins j = Jenkins.get();
        // The plugin was renamed from workflow-cps-global-lib; accept both ids.
        boolean libs = j.getPlugin("pipeline-groovy-lib") != null || j.getPlugin("workflow-cps-global-lib") != null;
        return libs && j.getPlugin("workflow-job") != null;
    }

    @NonNull
    @Override
    public CollectorResult collect(@NonNull ScanContext ctx) throws InterruptedException {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return run(ctx);
        }
    }

    // ------------------------------------------------------------- state

    /** What was learned about one configured library. */
    private static final class Usage {
        final ConfiguredLibrary lib;
        final Set<String> versions = new TreeSet<>();
        int runsInScope;
        int neverRunInScope;
        int loadedByRuns;
        int namedByScripts;
        long newestMillis = -1;
        Path newestDir;
        Set<String> newestVars = Set.of();

        Usage(ConfiguredLibrary lib) {
            this.lib = lib;
        }
    }

    private record Neverran(String jobFullName) {}

    /** One library source file from a checkout. */
    private record LibFile(Usage owner, boolean isVar, String name, String pkg, String simple, LibraryText.Scan scan) {}

    private CollectorResult run(ScanContext ctx) throws InterruptedException {
        Jenkins jenkins = Jenkins.get();
        List<ConfiguredLibrary> configured = new ArrayList<>(LibraryInventory.global());
        boolean folderPlugin = jenkins.getPlugin("cloudbees-folder") != null;

        // ---- pass 1: every folder (for its libraries) and every pipeline job (for its latest run)
        List<LibraryInventory.RunFacts> runs = new ArrayList<>();
        List<Neverran> neverRan = new ArrayList<>();
        Set<String> jobIdentifiers = new HashSet<>();
        Set<String> jobQualified = new HashSet<>();
        Map<String, Set<String>> wildcardTokens = new HashMap<>();
        int dynamicLibraryRefs = 0;
        int dynamicMarkers = 0;
        int unreadable = 0;
        List<LibraryText.LibraryRef> namedRefs = new ArrayList<>();
        List<String> namedRefJobs = new ArrayList<>();

        for (Item item : Items.allItems2(ACL.SYSTEM2, jenkins, Item.class)) {
            ctx.checkpoint();
            try {
                if (folderPlugin) {
                    configured.addAll(FolderLibrarySource.of(item));
                }
                if (item instanceof Job<?, ?> job) {
                    LibraryInventory.RunFacts f = LibraryInventory.examine(job);
                    if (f == null) {
                        continue;
                    }
                    if (f.buildDir() == null) {
                        neverRan.add(new Neverran(f.jobFullName()));
                        continue;
                    }
                    runs.add(f);
                    for (String script : f.scripts()) {
                        LibraryText.Scan s = LibraryText.scan(script);
                        jobIdentifiers.addAll(s.identifiers());
                        jobQualified.addAll(s.qualifiedNames());
                        for (String pkg : s.wildcardPackages()) {
                            wildcardTokens
                                    .computeIfAbsent(pkg, k -> new HashSet<>())
                                    .addAll(s.identifiers());
                        }
                        for (LibraryText.LibraryRef r : s.libraries()) {
                            namedRefs.add(r);
                            namedRefJobs.add(f.jobFullName());
                        }
                        dynamicLibraryRefs += s.dynamicLibraryRefs();
                        dynamicMarkers += s.dynamicCallMarkers();
                    }
                }
            } catch (BudgetExceededException e) {
                throw e;
            } catch (RuntimeException e) {
                unreadable++;
            }
        }
        if (configured.isEmpty()) {
            return CollectorResult.complete();
        }

        // ---- attribute what the runs saw to configured libraries (innermost scope wins, as Jenkins resolves it)
        Map<String, Usage> usage = new HashMap<>();
        for (ConfiguredLibrary c : configured) {
            usage.put(c.key(), new Usage(c));
        }
        for (Usage u : usage.values()) {
            for (LibraryInventory.RunFacts f : runs) {
                if (u.lib.covers(f.jobFullName())) {
                    u.runsInScope++;
                }
            }
            for (Neverran n : neverRan) {
                if (u.lib.covers(n.jobFullName())) {
                    u.neverRunInScope++;
                }
            }
        }
        for (LibraryInventory.RunFacts f : runs) {
            for (LibraryInventory.Loaded l : f.loaded()) {
                Usage u = resolve(configured, usage, f.jobFullName(), l.name());
                if (u == null) {
                    continue; // loaded but no longer configured: nothing to report on
                }
                u.loadedByRuns++;
                u.versions.add(l.version());
                if (f.timeMillis() > u.newestMillis) {
                    Path dir = LibraryInventory.checkoutDir(f.buildDir(), l.dirName());
                    if (dir != null) {
                        u.newestMillis = f.timeMillis();
                        u.newestDir = dir;
                        u.newestVars = l.variables();
                    }
                }
            }
        }
        for (int i = 0; i < namedRefs.size(); i++) {
            Usage u = resolve(
                    configured, usage, namedRefJobs.get(i), namedRefs.get(i).name());
            if (u != null) {
                u.namedByScripts++;
            }
        }

        // ---- pass 2: the files of the newest checkout of each loaded library
        int[] unreadableFiles = new int[1];
        List<LibFile> files = new ArrayList<>();
        Map<String, Integer> identFileCount = new HashMap<>();
        Map<String, Integer> qualFileCount = new HashMap<>();
        for (Usage u : usage.values()) {
            if (u.newestDir == null) {
                continue;
            }
            for (LibFile lf : readCheckout(ctx, u, unreadableFiles)) {
                files.add(lf);
                lf.scan().identifiers().forEach(t -> identFileCount.merge(t, 1, Integer::sum));
                lf.scan().qualifiedNames().forEach(t -> qualFileCount.merge(t, 1, Integer::sum));
                for (String pkg : lf.scan().wildcardPackages()) {
                    wildcardTokens
                            .computeIfAbsent(pkg, k -> new HashSet<>())
                            .addAll(lf.scan().identifiers());
                }
                dynamicMarkers += lf.scan().dynamicCallMarkers();
                dynamicLibraryRefs += lf.scan().dynamicLibraryRefs();
            }
        }

        // ---- verdicts (only now: everything has been read)
        unreadable += unreadableFiles[0];
        Gaps gaps = new Gaps(unreadable);
        for (Usage u : usage.values()) {
            if (u.runsInScope == 0) {
                continue; // nothing ran in its scope: no basis for any claim
            }
            if (u.loadedByRuns == 0 && u.namedByScripts == 0) {
                emitUnreferenced(ctx, u, dynamicLibraryRefs, gaps);
            }
            if (u.newestDir != null) {
                emitUnusedContent(
                        ctx,
                        u,
                        files,
                        jobIdentifiers,
                        jobQualified,
                        identFileCount,
                        qualFileCount,
                        wildcardTokens,
                        dynamicMarkers,
                        gaps);
            }
        }
        if (unreadable > 0) {
            return CollectorResult.partial(unreadable + " item(s) could not be examined");
        }
        return CollectorResult.complete();
    }

    private static Usage resolve(
            List<ConfiguredLibrary> configured, Map<String, Usage> usage, String jobFullName, String libName) {
        ConfiguredLibrary best = null;
        for (ConfiguredLibrary c : configured) {
            if (c.name().equals(libName) && c.covers(jobFullName)) {
                if (best == null || c.ownerPath().length() > best.ownerPath().length()) {
                    best = c;
                }
            }
        }
        return best == null ? null : usage.get(best.key());
    }

    private List<LibFile> readCheckout(ScanContext ctx, Usage u, int[] unreadable) throws InterruptedException {
        List<LibFile> out = new ArrayList<>();
        Path vars = u.newestDir.resolve("vars");
        Path src = u.newestDir.resolve("src");
        int budget = MAX_FILES_PER_LIBRARY;
        try {
            if (Files.isDirectory(vars, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> s = Files.list(vars)) {
                    for (Path p : (Iterable<Path>) s.filter(LibrariesPaths::isGroovy)::iterator) {
                        ctx.checkpoint();
                        String text = read(p);
                        if (text != null && budget-- > 0) {
                            String name = LibrariesPaths.stem(p);
                            out.add(new LibFile(u, true, name, "", name, LibraryText.scan(text)));
                        }
                    }
                }
            }
            if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> s = Files.walk(src, 16)) {
                    for (Path p : (Iterable<Path>) s.filter(LibrariesPaths::isGroovy)::iterator) {
                        ctx.checkpoint();
                        String text = read(p);
                        if (text != null && budget-- > 0) {
                            Path rel = src.relativize(p);
                            Path parent = rel.getParent();
                            String pkg = parent == null
                                    ? ""
                                    : parent.toString().replace('/', '.').replace('\\', '.');
                            String simple = LibrariesPaths.stem(p);
                            out.add(new LibFile(
                                    u,
                                    false,
                                    pkg.isEmpty() ? simple : pkg + "." + simple,
                                    pkg,
                                    simple,
                                    LibraryText.scan(text)));
                        }
                    }
                }
            }
        } catch (IOException e) {
            unreadable[0]++; // fewer files read: every verdict for this library gets a caveat
        }
        return out;
    }

    private static String read(Path p) throws IOException {
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.size(p) > MAX_FILE_BYTES) {
            return null;
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------- findings

    /** Items that could not be read: their scripts are missing from the evidence, so every verdict is less sure. */
    private record Gaps(int unreadable) {
        int apply(List<Evidence> ev) {
            if (unreadable == 0) {
                return 0;
            }
            ev.add(new Evidence(
                    SRC,
                    unreadable + " job(s) or checkout(s) could not be read; their scripts are not in this evidence",
                    0));
            return 1;
        }
    }

    private void emitUnreferenced(ScanContext ctx, Usage u, int dynamicLibraryRefs, Gaps gaps) {
        List<Evidence> ev = new ArrayList<>();
        int caveats = 0;
        ev.add(new Evidence(
                SRC,
                "Not loaded by the latest run of any of the " + u.runsInScope + " pipeline job(s) that could use it",
                35));
        ev.add(new Evidence(SRC, "No @Library or library step names it in the pipeline source of those runs", 25));
        if (u.runsInScope >= 2 * BROAD) {
            ev.add(new Evidence(SRC, "Broad coverage: " + u.runsInScope + " jobs inspected", 15));
        }
        ev.add(new Evidence(
                SRC,
                "Configured version: "
                        + (u.lib.defaultVersion() == null ? "none (scripts must name one)" : u.lib.defaultVersion())
                        + (u.lib.implicit() ? "; loaded implicitly into every job" : ""),
                0));
        if (dynamicLibraryRefs > 0) {
            caveats++;
            ev.add(new Evidence(
                    SRC,
                    dynamicLibraryRefs + " library request(s) use a variable or GString name and cannot be resolved",
                    0));
        }
        if (u.neverRunInScope > 0) {
            caveats++;
            ev.add(new Evidence(
                    SRC, u.neverRunInScope + " pipeline job(s) in scope have never run and could use it", 0));
        }
        caveats += gaps.apply(ev);
        notChecked(ev);
        emit(ctx, "library.unreferenced", "library", u.lib.key(), u.lib.display(), ev, caveats);
    }

    private void emitUnusedContent(
            ScanContext ctx,
            Usage u,
            List<LibFile> files,
            Set<String> jobIdentifiers,
            Set<String> jobQualified,
            Map<String, Integer> identFileCount,
            Map<String, Integer> qualFileCount,
            Map<String, Set<String>> wildcardTokens,
            int dynamicMarkers,
            Gaps gaps) {
        Map<String, LibFile> varFiles = new HashMap<>();
        for (LibFile f : files) {
            if (f.owner() == u && f.isVar()) {
                varFiles.put(f.name(), f);
            }
        }
        int broad = u.runsInScope >= BROAD ? 15 : 0;
        for (String v : new TreeSet<>(u.newestVars)) {
            LibFile own = varFiles.get(v);
            int ownCount = own != null && own.scan().identifiers().contains(v) ? 1 : 0;
            boolean used = jobIdentifiers.contains(v) || identFileCount.getOrDefault(v, 0) - ownCount > 0;
            if (!used) {
                List<Evidence> ev = new ArrayList<>();
                ev.add(new Evidence(
                        SRC,
                        "No pipeline source of the " + u.runsInScope
                                + " inspected job(s), and no other file of the library, mentions '" + v + "'",
                        50));
                if (broad > 0) {
                    ev.add(new Evidence(SRC, "Broad coverage: " + u.runsInScope + " jobs inspected", broad));
                }
                contentContext(ev, u);
                int caveats = dynamicCaveat(ev, dynamicMarkers);
                emit(
                        ctx,
                        "library.unused_variable",
                        "library_var",
                        u.lib.key() + ":" + v,
                        u.lib.name() + ": " + v,
                        ev,
                        caveats);
            }
        }
        for (LibFile f : files) {
            if (f.owner() != u || f.isVar()) {
                continue;
            }
            if (!classUsed(f, files, jobIdentifiers, jobQualified, identFileCount, qualFileCount, wildcardTokens)) {
                List<Evidence> ev = new ArrayList<>();
                ev.add(new Evidence(
                        SRC,
                        "No pipeline source of the " + u.runsInScope
                                + " inspected job(s), and no other file of the library, refers to '" + f.name() + "'",
                        45));
                if (broad > 0) {
                    ev.add(new Evidence(SRC, "Broad coverage: " + u.runsInScope + " jobs inspected", broad));
                }
                contentContext(ev, u);
                int caveats = dynamicCaveat(ev, dynamicMarkers) + gaps.apply(ev);
                if (!f.pkg().isEmpty() && wildcardTokens.containsKey(f.pkg())) {
                    caveats++;
                    ev.add(new Evidence(
                            SRC, "Scripts import '" + f.pkg() + ".*'; the class may be reached that way", 0));
                }
                emit(
                        ctx,
                        "library.unused_class",
                        "library_class",
                        u.lib.key() + ":" + f.name(),
                        u.lib.name() + ": " + f.name(),
                        ev,
                        caveats);
            }
        }
    }

    private static boolean classUsed(
            LibFile f,
            List<LibFile> files,
            Set<String> jobIdentifiers,
            Set<String> jobQualified,
            Map<String, Integer> identFileCount,
            Map<String, Integer> qualFileCount,
            Map<String, Set<String>> wildcardTokens) {
        if (f.pkg().isEmpty()) { // default package: only the simple name can be matched
            int own = f.scan().identifiers().contains(f.simple()) ? 1 : 0;
            return jobIdentifiers.contains(f.simple()) || identFileCount.getOrDefault(f.simple(), 0) - own > 0;
        }
        int ownQ = f.scan().qualifiedNames().contains(f.name()) ? 1 : 0;
        if (jobQualified.contains(f.name()) || qualFileCount.getOrDefault(f.name(), 0) - ownQ > 0) {
            return true;
        }
        for (LibFile other : files) { // same-package classes refer to each other by simple name, no import needed
            if (other != f
                    && other.owner() == f.owner()
                    && !other.isVar()
                    && other.pkg().equals(f.pkg())
                    && other.scan().identifiers().contains(f.simple())) {
                return true;
            }
        }
        Set<String> viaWildcard = wildcardTokens.get(f.pkg());
        return viaWildcard != null && viaWildcard.contains(f.simple());
    }

    private static void contentContext(List<Evidence> ev, Usage u) {
        ev.add(new Evidence(
                SRC,
                "Library " + u.lib.display() + "; versions loaded by recent runs: " + String.join(", ", u.versions),
                0));
        notChecked(ev);
    }

    private static int dynamicCaveat(List<Evidence> ev, int dynamicMarkers) {
        if (dynamicMarkers > 0) {
            ev.add(new Evidence(
                    SRC,
                    dynamicMarkers
                            + " dynamic invocation(s) (evaluate, invokeMethod, computed method names, Class.forName) could reach it",
                    0));
            return 1;
        }
        return 0;
    }

    private static void notChecked(List<Evidence> ev) {
        ev.add(new Evidence(
                SRC,
                "Not checked: older runs, branches that never ran, Jenkinsfiles outside Jenkins, and use by name from other systems",
                0));
    }

    private void emit(
            ScanContext ctx,
            String type,
            String kind,
            String fullName,
            String display,
            List<Evidence> ev,
            int caveats) {
        ctx.emit(new FindingCandidate(
                type,
                Category.SHARED_LIBRARIES,
                Subject.of(kind, fullName, display),
                Severity.LOW,
                ConfidenceScorer.score(ev, caveats),
                ev,
                Messages.SharedLibrariesCollector_SuggestedAction()));
    }
}
