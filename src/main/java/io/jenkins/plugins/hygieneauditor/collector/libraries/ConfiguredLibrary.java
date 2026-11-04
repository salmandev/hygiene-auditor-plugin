package io.jenkins.plugins.hygieneauditor.collector.libraries;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A library as configured in Jenkins.
 *
 * @param ownerPath empty for a Jenkins-wide library, else the full name of the folder that defines it
 */
record ConfiguredLibrary(
        @NonNull String ownerPath,
        @NonNull String name,
        @CheckForNull String defaultVersion,
        boolean implicit) {

    @NonNull
    String key() {
        return ownerPath + ":" + name;
    }

    /** True if a job at {@code jobFullName} can see this library (global, or defined by an ancestor folder). */
    boolean covers(@NonNull String jobFullName) {
        return ownerPath.isEmpty() || jobFullName.startsWith(ownerPath + "/");
    }

    @NonNull
    String display() {
        return ownerPath.isEmpty() ? name + " (Jenkins)" : name + " (folder " + ownerPath + ")";
    }
}
