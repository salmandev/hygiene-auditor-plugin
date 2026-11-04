package io.jenkins.plugins.hygieneauditor.policy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import java.util.Locale;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * One exemption. A finding matches when <em>every</em> field that is set matches; a rule with no matcher is rejected
 * (it would exempt everything). Matching findings are never recorded.
 *
 * <ul>
 *   <li>{@code category}: JOBS, AGENTS, CREDENTIALS, SHARED_LIBRARIES, PLUGINS
 *   <li>{@code type}: finding type, {@code *} wildcard allowed (e.g. {@code credential.*})
 *   <li>{@code subject}: Java regex searched in the subject name (a credential's id, else the full name), e.g.
 *       {@code ^prod-.*}
 *   <li>{@code folder}: exempts a folder and everything below it
 *   <li>{@code reason}: documentation only
 * </ul>
 */
public class IgnoreRule {
    private static final int MAX_NAME = 1024;
    private static final long MATCH_TIMEOUT_MILLIS = 50;
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IgnoreRule.class.getName());

    private final String category;
    private String type;
    private String subject;
    private String folder;
    private String reason;

    private transient Category categoryEnum;
    private transient Pattern typePattern;
    private transient Pattern subjectPattern;

    @DataBoundConstructor
    public IgnoreRule(@CheckForNull String category) {
        this.category = blankToNull(category);
        if (this.category != null) {
            try {
                this.categoryEnum = Category.valueOf(this.category.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown category '" + category + "'. Valid: " + java.util.Arrays.toString(Category.values()));
            }
        }
    }

    @CheckForNull
    public String getCategory() {
        return category;
    }

    @CheckForNull
    public String getType() {
        return type;
    }

    @DataBoundSetter
    public void setType(@CheckForNull String type) {
        this.type = blankToNull(type);
        this.typePattern = null;
        if (this.type != null) {
            String[] parts = this.type.split("\\*", -1);
            StringBuilder re = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    re.append(".*");
                }
                re.append(Pattern.quote(parts[i]));
            }
            this.typePattern = Pattern.compile(re.toString());
        }
    }

    @CheckForNull
    public String getSubject() {
        return subject;
    }

    @DataBoundSetter
    public void setSubject(@CheckForNull String subject) {
        this.subject = blankToNull(subject);
        this.subjectPattern = null;
        if (this.subject != null) {
            try {
                this.subjectPattern = Pattern.compile(this.subject);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid subject regex '" + subject + "': " + e.getDescription());
            }
        }
    }

    @CheckForNull
    public String getFolder() {
        return folder;
    }

    @DataBoundSetter
    public void setFolder(@CheckForNull String folder) {
        String f = blankToNull(folder);
        while (f != null && f.endsWith("/")) {
            f = f.substring(0, f.length() - 1);
        }
        this.folder = f == null || f.isEmpty() ? null : f;
    }

    @CheckForNull
    public String getReason() {
        return reason;
    }

    @DataBoundSetter
    public void setReason(@CheckForNull String reason) {
        this.reason = blankToNull(reason);
    }

    /** Throws if the rule would match everything. Called when the rule list is installed. */
    public void validate() {
        if (category == null && type == null && subject == null && folder == null) {
            throw new IllegalArgumentException(
                    "An ignore rule needs at least one of category, type, subject or folder");
        }
    }

    public boolean matches(@NonNull Finding f) {
        if (categoryEnum != null && f.category() != categoryEnum) {
            return false;
        }
        if (typePattern != null && !typePattern.matcher(f.type()).matches()) {
            return false;
        }
        if (folder != null && !SubjectPaths.inFolder(f, folder)) {
            return false;
        }
        if (subjectPattern != null) {
            String name = SubjectPaths.matchName(f);
            if (name.length() > MAX_NAME) {
                name = name.substring(0, MAX_NAME);
            }
            try {
                return subjectPattern
                        .matcher(new DeadlineCharSequence(name, MATCH_TIMEOUT_MILLIS))
                        .find();
            } catch (DeadlineCharSequence.MatchTimeoutException e) {
                // A pattern that backtracks catastrophically must not stall the scan: treat as "no match".
                LOGGER.log(Level.WARNING, "Ignore rule regex timed out and was treated as not matching: {0}", subject);
                return false;
            }
        }
        return true;
    }

    /** Re-creates transient state after XStream deserialisation. */
    protected Object readResolve() {
        IgnoreRule r = new IgnoreRule(category);
        r.setType(type);
        r.setSubject(subject);
        r.setFolder(folder);
        r.setReason(reason);
        return r;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
