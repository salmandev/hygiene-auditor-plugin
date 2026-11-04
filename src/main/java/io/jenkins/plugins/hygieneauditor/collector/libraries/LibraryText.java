package io.jenkins.plugins.hygieneauditor.collector.libraries;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Pipeline / library source as <b>text</b> (never executed) and extracts what the library collector needs:
 * the words used, qualified class names, wildcard imports, the libraries a script asks for, and markers of dynamic
 * invocation that make "unused" harder to claim.
 *
 * <p>Comments are not stripped: a name mentioned only in a comment counts as used. That errs towards "used".
 */
public final class LibraryText {

    /** A library a script asks for. {@code version} is null when the script gives none. */
    public record LibraryRef(
            @NonNull String name, @CheckForNull String version) {}

    /**
     * @param identifiers every word in the text
     * @param qualifiedNames dotted names ending in a capitalised segment, e.g. {@code org.acme.Util}
     * @param wildcardPackages packages imported with {@code .*}
     * @param libraries libraries requested with literal names
     * @param dynamicLibraryRefs requests whose name is not a literal (variables, GStrings)
     * @param dynamicCallMarkers uses of {@code evaluate}, {@code invokeMethod}, {@code Class.forName}, computed method
     *     names, ...
     */
    public record Scan(
            @NonNull Set<String> identifiers,
            @NonNull Set<String> qualifiedNames,
            @NonNull Set<String> wildcardPackages,
            @NonNull List<LibraryRef> libraries,
            int dynamicLibraryRefs,
            int dynamicCallMarkers) {}

    static final int MAX_TEXT = 4_000_000;
    private static final int MAX_IDENTIFIER = 128;

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern QUALIFIED =
            Pattern.compile("\\b[a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)*\\.[A-Z][A-Za-z0-9_]*");
    private static final Pattern PACKAGE_LINE = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+");
    private static final Pattern WILDCARD = Pattern.compile("\\bimport\\s+(?:static\\s+)?([a-z_][\\w.]*)\\.\\*");
    private static final Pattern ANNOTATION = Pattern.compile("@Library\\s*\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([^'\\n]*)'|\"([^\"\\n]*)\"");
    private static final Pattern STEP_LITERAL =
            Pattern.compile("\\blibrary\\s*\\(?\\s*(?:identifier\\s*:\\s*)?(?:'([^'\\n]*)'|\"([^\"\\n]*)\")");
    private static final Pattern STEP_VARIABLE =
            Pattern.compile("\\blibrary\\s*\\(\\s*(?!identifier\\s*:)(?!['\"])([A-Za-z_][\\w.]*)\\s*[,)]");
    private static final Pattern DYNAMIC_CALL =
            Pattern.compile("\\b(?:evaluate|invokeMethod|getProperty|newInstance)\\s*\\(|\\bClass\\s*\\.\\s*forName\\b"
                    + "|\\bthis\\s*\\.\\s*[\"']|\"\\$\\{[^}\\n]*\\}\"\\s*\\(");

    private LibraryText() {}

    @NonNull
    public static Scan scan(@NonNull String text) {
        Set<String> ids = new HashSet<>();
        Set<String> qualified = new HashSet<>();
        Set<String> wildcards = new HashSet<>();
        List<LibraryRef> libs = new ArrayList<>();
        if (text.length() > MAX_TEXT) {
            return new Scan(ids, qualified, wildcards, libs, 0, 0);
        }
        // Library names are not uses of the things a library defines: blank out @Library(...) and library-step
        // arguments before collecting words, so a variable named like its own library does not look used.
        String forWords = ANNOTATION.matcher(text).replaceAll(" ");
        forWords = STEP_LITERAL.matcher(forWords).replaceAll(" ");
        // Package names are not uses either: drop 'package x.y' lines and wildcard imports, and reduce a dotted class
        // name (org.acme.Util) to its class name, so the package segments cannot match a same-named variable.
        forWords = PACKAGE_LINE.matcher(forWords).replaceAll(" ");
        forWords = WILDCARD.matcher(forWords).replaceAll(" ");
        forWords = lastSegments(forWords);
        Matcher w = WORD.matcher(forWords);
        while (w.find()) {
            if (w.end() - w.start() <= MAX_IDENTIFIER) {
                ids.add(w.group());
            }
        }
        Matcher q = QUALIFIED.matcher(text);
        while (q.find()) {
            qualified.add(q.group());
        }
        Matcher wc = WILDCARD.matcher(text);
        while (wc.find()) {
            wildcards.add(wc.group(1));
        }

        int dynamicLibs = 0;
        Matcher a = ANNOTATION.matcher(text);
        while (a.find()) {
            String args = a.group(1);
            Matcher s = QUOTED.matcher(args);
            while (s.find()) {
                dynamicLibs += addLibrary(libs, s.group(1) != null ? s.group(1) : s.group(2));
            }
            if (!QUOTED.matcher(args)
                    .replaceAll("")
                    .replaceAll("[\\s,\\[\\]]", "")
                    .isEmpty()) {
                dynamicLibs++; // @Library(someVariable)
            }
        }
        Matcher st = STEP_LITERAL.matcher(text);
        while (st.find()) {
            dynamicLibs += addLibrary(libs, st.group(1) != null ? st.group(1) : st.group(2));
        }
        Matcher sv = STEP_VARIABLE.matcher(text);
        while (sv.find()) {
            dynamicLibs++;
        }

        int markers = 0;
        Matcher d = DYNAMIC_CALL.matcher(text);
        while (d.find()) {
            markers++;
        }
        return new Scan(ids, qualified, wildcards, libs, dynamicLibs, markers);
    }

    private static String lastSegments(String text) {
        Matcher m = QUALIFIED.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            String q = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(" " + q.substring(q.lastIndexOf('.') + 1) + " "));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** @return 1 if the requested name was dynamic (not recorded), else 0 */
    private static int addLibrary(List<LibraryRef> into, String spec) {
        if (spec.indexOf('$') >= 0 || spec.isBlank()) {
            return 1;
        }
        int at = spec.indexOf('@');
        String name = (at < 0 ? spec : spec.substring(0, at)).trim();
        String version = at < 0 ? null : spec.substring(at + 1).trim();
        if (!name.isEmpty()) {
            into.add(new LibraryRef(name, version == null || version.isEmpty() ? null : version));
        }
        return 0;
    }
}
