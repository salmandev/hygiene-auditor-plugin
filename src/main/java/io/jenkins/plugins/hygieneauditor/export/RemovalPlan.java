package io.jenkins.plugins.hygieneauditor.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns findings into something an administrator can review and run by hand. <b>The plugin never executes any of it.</b>
 *
 * <p>Safety design:
 * <ul>
 *   <li>The Groovy script is a fixed program plus a data list of string literals. Finding data is only ever placed
 *       inside a literal, through {@link #groovyLiteral}; it can never become code.
 *   <li>It defaults to {@code DRY_RUN = true}, and refuses to touch a job or agent that was active after the plan was
 *       generated.
 *   <li>Shell output single-quotes every value; values with control characters are skipped, not escaped.
 * </ul>
 */
public final class RemovalPlan {

    public enum Format {
        GROOVY("groovy", "text/plain"),
        CLI("sh", "text/plain"),
        CHECKLIST("md", "text/markdown");

        private final String extension;
        private final String contentType;

        Format(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String extension() {
            return extension;
        }

        public String contentType() {
            return contentType;
        }

        public static Format parse(String s) {
            if (s == null || s.isBlank()) {
                return GROOVY;
            }
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        }
    }

    private RemovalPlan() {}

    @NonNull
    public static String generate(
            @NonNull List<Finding> findings,
            @NonNull Format format,
            @NonNull String requestedBy,
            @NonNull Instant now) {
        return switch (format) {
            case GROOVY -> groovy(findings, requestedBy, now);
            case CLI -> cli(findings, requestedBy, now);
            case CHECKLIST -> checklist(findings, requestedBy, now);
        };
    }

    // ------------------------------------------------------------------ Groovy

    private static String groovy(List<Finding> findings, String by, Instant now) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("// Hygiene Auditor removal plan\n");
        sb.append("// Generated ")
                .append(now)
                .append(" for ")
                .append(line(by))
                .append(": ")
                .append(findings.size())
                .append(" finding(s).\n");
        sb.append("// The Hygiene Auditor NEVER runs this. Read every entry, make a backup, then run it yourself in\n");
        sb.append("// Manage Jenkins > Script Console. It prints what it would do and changes nothing until you set\n");
        sb.append("// DRY_RUN to false. Evidence is a snapshot: confirm each item still holds.\n\n");
        sb.append("import jenkins.model.Jenkins\n\n");
        sb.append("final boolean DRY_RUN = true\n");
        sb.append("final long PLAN_TIME = ").append(now.toEpochMilli()).append("L\n\n");
        sb.append("final List<Map<String, String>> PLAN = [\n");
        List<String> manual = new ArrayList<>();
        for (Finding f : findings) {
            String entry = groovyEntry(f);
            if (entry == null) {
                manual.add(
                        line(f.type() + " " + f.subject().fullName() + " (no automatic removal; see the checklist)"));
            } else {
                sb.append("    ").append(entry).append(",\n");
            }
        }
        sb.append("]\n\n");
        sb.append(GROOVY_BODY);
        if (!manual.isEmpty()) {
            sb.append("\n// Findings with no automatic removal (handle by hand):\n");
            manual.forEach(m -> sb.append("//   ").append(m).append('\n'));
        }
        return sb.toString();
    }

    private static String groovyEntry(Finding f) {
        String why = f.type() + ", confidence " + f.confidence() + ": " + firstEvidence(f);
        switch (f.type()) {
            case "job.never_built":
            case "job.stale":
            case "job.disabled":
                return "[kind: " + groovyLiteral("job") + ", name: "
                        + groovyLiteral(f.subject().fullName()) + ", why: " + groovyLiteral(why) + "]";
            case "agent.offline":
                return "[kind: " + groovyLiteral("agent") + ", name: "
                        + groovyLiteral(f.subject().fullName()) + ", why: " + groovyLiteral(why) + "]";
            case "credential.unused":
                String[] p = f.subject().fullName().split(":", 3);
                if (p.length != 3) {
                    return null;
                }
                return "[kind: " + groovyLiteral("credential") + ", owner: " + groovyLiteral(p[0]) + ", domain: "
                        + groovyLiteral(p[1]) + ", id: " + groovyLiteral(p[2]) + ", why: " + groovyLiteral(why) + "]";
            default:
                return null;
        }
    }

    /**
     * A single-quoted Groovy string literal containing exactly {@code s}.
     *
     * <p>Only octal and single-letter escapes are emitted, never a unicode escape: Groovy, like Java, translates
     * {@code \\uXXXX} <em>before</em> lexing, so an escaped quote written that way would end the string and let data
     * become code. Backslash and quote are written as octal ({@code \\134}, {@code \\047}), which means a literal
     * backslash is never followed by {@code u} in the output, whatever the input contains.
     */
    static String groovyLiteral(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\134");
                case '\'' -> sb.append("\\047");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\%03o", (int) c));
                    } else {
                        sb.append(c); // includes U+2028/2029/0085: not line terminators in Groovy source
                    }
                }
            }
        }
        return sb.append('\'').toString();
    }

    private static final String GROOVY_BODY = """
            int removed = 0
            int skipped = 0
            def jenkins = Jenkins.get()
            def say = { String m -> println((DRY_RUN ? '[dry-run] ' : '') + m) }

            PLAN.each { Map<String, String> a ->
                switch (a.kind) {
                    case 'job':
                        def item = jenkins.getItemByFullName(a.name)
                        if (item == null) { println "already gone: job ${a.name}"; break }
                        def last = (item instanceof hudson.model.Job) ? item.lastBuild : null
                        if (last != null && last.timeInMillis > PLAN_TIME) {
                            println "SKIPPED (built after this plan was generated): job ${a.name}"; skipped++; break
                        }
                        say "delete job ${a.name} -- ${a.why}"
                        if (!DRY_RUN) { item.delete(); removed++ }
                        break
                    case 'agent':
                        def node = jenkins.getNode(a.name)
                        if (node == null) { println "already gone: agent ${a.name}"; break }
                        if (node.toComputer()?.isOnline()) {
                            println "SKIPPED (online now): agent ${a.name}"; skipped++; break
                        }
                        say "remove agent ${a.name} -- ${a.why}"
                        if (!DRY_RUN) { jenkins.removeNode(node); removed++ }
                        break
                    case 'credential':
                        def ctx = a.owner ? jenkins.getItemByFullName(a.owner) : jenkins
                        if (ctx == null) { println "already gone: owner ${a.owner} of credential ${a.id}"; break }
                        def hit = false
                        for (def store : com.cloudbees.plugins.credentials.CredentialsProvider.lookupStores(ctx)) {
                            for (def dom : store.domains) {
                                if ((dom.name ?: '_') != a.domain) continue
                                for (def cred : store.getCredentials(dom)) {
                                    if (cred instanceof com.cloudbees.plugins.credentials.common.IdCredentials && cred.id == a.id) {
                                        hit = true
                                        say "delete credential ${a.id} (owner '${a.owner}', domain ${a.domain}) -- ${a.why}"
                                        if (!DRY_RUN) { store.removeCredentials(dom, cred); removed++ }
                                    }
                                }
                            }
                        }
                        if (!hit) println "already gone: credential ${a.id}"
                        break
                    default:
                        println "unknown kind ${a.kind}: ignored"
                }
            }
            println "${DRY_RUN ? 'DRY RUN: nothing was changed.' : 'Removed ' + removed + ' item(s).'} Skipped: ${skipped}."
            """;

    // --------------------------------------------------------------------- CLI

    private static String cli(List<Finding> findings, String by, Instant now) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("#!/bin/sh\n");
        sb.append("# Hygiene Auditor removal plan, generated ")
                .append(now)
                .append(" for ")
                .append(line(by))
                .append(".\n");
        sb.append("# The Hygiene Auditor NEVER runs this. Review each line and back up first.\n");
        sb.append("# Every command is commented out; remove the leading '# ' from the ones you accept.\n");
        sb.append("# Credential store ids follow the Credentials plugin CLI documentation: confirm them against\n");
        sb.append(
                "# 'list-credentials' first. Folder-scoped credentials are not generated here; use the Groovy format.\n");
        sb.append(": \"${JENKINS_URL:?set JENKINS_URL}\"\n");
        sb.append("CLI=\"java -jar jenkins-cli.jar -s $JENKINS_URL -auth @your-credentials-file\"\n\n");
        for (Finding f : findings) {
            String why = line(f.type() + ", confidence " + f.confidence() + ": " + firstEvidence(f));
            String name = f.subject().fullName();
            if (unsafeForShell(name) || unsafeForShell(why)) {
                sb.append("# SKIPPED (unsafe characters in name): ")
                        .append(line(f.type()))
                        .append(" finding ")
                        .append(f.id())
                        .append('\n');
                continue;
            }
            sb.append("# ").append(why).append('\n');
            switch (f.type()) {
                case "job.never_built", "job.stale", "job.disabled" ->
                    sb.append("# $CLI delete-job ").append(shq(name)).append("\n\n");
                case "agent.offline" ->
                    sb.append("# $CLI delete-node ").append(shq(name)).append("\n\n");
                case "credential.unused" -> {
                    String[] p = name.split(":", 3);
                    if (p.length == 3 && p[0].isEmpty()) {
                        sb.append("# $CLI delete-credentials system::system::jenkins ")
                                .append(shq(p[1]))
                                .append(' ')
                                .append(shq(p[2]))
                                .append("\n\n");
                    } else {
                        sb.append("# (folder credential: use the Groovy format)\n\n");
                    }
                }
                default -> sb.append("# (no automatic removal for this type)\n\n");
            }
        }
        return sb.toString();
    }

    private static boolean unsafeForShell(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // --------------------------------------------------------------- checklist

    private static String checklist(List<Finding> findings, String by, Instant now) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# Hygiene Auditor removal checklist\n\n");
        sb.append("Generated ")
                .append(now)
                .append(" for ")
                .append(line(by))
                .append(" — ")
                .append(findings.size())
                .append(" finding(s).\n\n");
        sb.append("The Hygiene Auditor changes nothing. Work through this list yourself.\n\n");
        sb.append("## Before you start\n\n");
        sb.append("- [ ] Back up `JENKINS_HOME` (at least `jobs/`, `nodes/`, `credentials.xml`, `secrets/`).\n");
        sb.append("- [ ] Treat each item as a question for its owner, not a verdict: the evidence is a snapshot.\n");
        sb.append("- [ ] For credentials: revoke the secret at its source first, then delete the credential.\n\n");
        sb.append("## Items\n\n");
        for (Finding f : findings) {
            sb.append("- [ ] **")
                    .append(md(f.subject().displayName()))
                    .append("** — `")
                    .append(f.type())
                    .append("`, severity ")
                    .append(f.severity())
                    .append(", confidence ")
                    .append(f.confidence())
                    .append('\n');
            for (Evidence e : f.evidence()) {
                sb.append("  - ").append(md(e.text())).append('\n');
            }
            sb.append("  - Suggested: ").append(md(f.suggestedAction())).append("\n\n");
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- helpers

    private static String firstEvidence(Finding f) {
        return f.evidence().isEmpty()
                ? "no evidence recorded"
                : f.evidence().get(0).text();
    }

    /** One line, no control characters: safe inside a comment. */
    private static String line(String s) {
        return s.replaceAll("[\\p{Cntrl}\\u2028\\u2029\\u0085]+", " ").trim();
    }

    private static String md(String s) {
        return line(s).replace("`", "'").replace("<", "&lt;");
    }
}
