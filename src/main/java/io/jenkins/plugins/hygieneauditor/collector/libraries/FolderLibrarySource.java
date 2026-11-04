package io.jenkins.plugins.hygieneauditor.collector.libraries;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import hudson.model.Item;
import java.util.ArrayList;
import java.util.List;
import org.jenkinsci.plugins.workflow.libs.FolderLibraries;
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration;

/** Folder-level libraries. Isolated because it needs the optional Folders plugin. */
final class FolderLibrarySource {
    private FolderLibrarySource() {}

    static List<ConfiguredLibrary> of(Item item) {
        List<ConfiguredLibrary> out = new ArrayList<>();
        if (item instanceof AbstractFolder<?> folder) {
            for (AbstractFolderProperty<?> p : folder.getProperties()) {
                if (p instanceof FolderLibraries fl) {
                    for (LibraryConfiguration c : fl.getLibraries()) {
                        out.add(new ConfiguredLibrary(
                                folder.getFullName(), c.getName(), c.getDefaultVersion(), c.isImplicit()));
                    }
                }
            }
        }
        return out;
    }
}
