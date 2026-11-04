package io.jenkins.plugins.hygieneauditor.policy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.model.Finding;

/** Interprets subject names: which folder a finding lives in, and the short name users write rules against. */
public final class SubjectPaths {
    private SubjectPaths() {}

    /** Folder path used for scoping: the job's full name, or a credential's owning folder. Null if not folder-scoped. */
    @CheckForNull
    public static String scopePath(@NonNull Finding f) {
        String n = f.subject().fullName();
        switch (f.subject().kind()) {
            case "job":
                return n;
            case "credential":
            case "library":
            case "library_var":
            case "library_class":
                int i = n.indexOf(':'); // ownerPath:... ; item names cannot contain ':'
                return i < 0 ? "" : n.substring(0, i);
            default:
                return null;
        }
    }

    public static boolean inFolder(@NonNull Finding f, @NonNull String folder) {
        String path = scopePath(f);
        return path != null && (path.equals(folder) || path.startsWith(folder + "/"));
    }

    /** The name rules match against: a credential's id, a library's variable/class/name, otherwise the full name. */
    @NonNull
    public static String matchName(@NonNull Finding f) {
        String n = f.subject().fullName();
        int first = n.indexOf(':');
        int second = first < 0 ? -1 : n.indexOf(':', first + 1);
        switch (f.subject().kind()) {
            case "credential": // owner:domain:id
            case "library_var": // owner:library:variable
            case "library_class": // owner:library:org.acme.Class
                return second < 0 ? n : n.substring(second + 1);
            case "library": // owner:library
                return first < 0 ? n : n.substring(first + 1);
            default:
                return n;
        }
    }
}
