package io.jenkins.plugins.hygieneauditor.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.regex.Pattern;

/**
 * Last line of defence against secret values reaching findings, logs, exports or error messages.
 *
 * <p>Collectors must never read secret values in the first place; this class is a conservative safety net for text
 * that is derived from untrusted content (job configs, Jenkinsfiles) or from exception messages. It favours
 * over-redaction.
 */
public final class Redactor {

    public static final String MASK = "[REDACTED]";

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*(?:PRIVATE KEY|CERTIFICATE|PGP[A-Z ]*BLOCK)-----[\\s\\S]*?(?:-----END [A-Z0-9 ]*-----|$)");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|passphrase|secret|token|api[_-]?key|private[_-]?key|client[_-]?secret)\\b"
                    + "(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&}\\])]+)");
    private static final Pattern AUTH_HEADER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://[^/\\s:@]+):[^/\\s@]+@");
    private static final Pattern KNOWN_TOKENS =
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{16,}"
                    + "|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,})\\b");

    private static final int MAX_MESSAGE = 300;

    private Redactor() {}

    /** Masks anything that looks like a secret. Never returns null. */
    @NonNull
    public static String redact(@CheckForNull String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = PEM.matcher(text).replaceAll(MASK);
        s = URL_USERINFO.matcher(s).replaceAll("$1:" + MASK + "@");
        s = KEY_VALUE.matcher(s).replaceAll("$1$2" + MASK);
        s = AUTH_HEADER.matcher(s).replaceAll("$1 " + MASK);
        s = KNOWN_TOKENS.matcher(s).replaceAll(MASK);
        return s;
    }

    /** Safe one-line description of a failure: class name plus redacted, truncated message. */
    @NonNull
    public static String describe(@NonNull Throwable t) {
        String msg = redact(t.getMessage()).replaceAll("\\s+", " ").trim();
        if (msg.length() > MAX_MESSAGE) {
            msg = msg.substring(0, MAX_MESSAGE) + "…";
        }
        return msg.isEmpty() ? t.getClass().getName() : t.getClass().getName() + ": " + msg;
    }

    /** Redacts, then truncates to {@code max} characters. */
    @NonNull
    public static String redactAndTruncate(@CheckForNull String text, int max) {
        String s = redact(text);
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
