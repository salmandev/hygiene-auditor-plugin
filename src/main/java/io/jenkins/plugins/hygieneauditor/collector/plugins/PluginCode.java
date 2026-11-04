package io.jenkins.plugins.hygieneauditor.collector.plugins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.PluginWrapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Tells meta-plugins from real ones. A suite such as {@code workflow-aggregator} contains no code of its own: it exists to
 * pull in other plugins, so "nothing uses it" is meaningless for it. Anything that cannot be inspected counts as having
 * code (we would rather report a plugin than wrongly exempt one).
 */
final class PluginCode {
    private PluginCode() {}

    static boolean hasCode(@NonNull PluginWrapper plugin) {
        try {
            Path root = new File(plugin.baseResourceURL.toURI()).toPath();
            return hasCode(root);
        } catch (URISyntaxException | RuntimeException | IOException e) {
            return true;
        }
    }

    /** @param pluginRoot the exploded plugin directory ({@code WEB-INF/classes}, {@code WEB-INF/lib/*.jar}) */
    static boolean hasCode(@NonNull Path pluginRoot) throws IOException {
        Path classes = pluginRoot.resolve("WEB-INF").resolve("classes");
        if (Files.isDirectory(classes)) {
            try (Stream<Path> s = Files.walk(classes)) {
                if (s.anyMatch(p -> p.toString().endsWith(".class"))) {
                    return true;
                }
            }
        }
        Path lib = pluginRoot.resolve("WEB-INF").resolve("lib");
        if (!Files.isDirectory(lib)) {
            return false;
        }
        try (Stream<Path> jars = Files.list(lib)) {
            for (Path jar : (Iterable<Path>) jars.filter(p -> p.toString().endsWith(".jar"))::iterator) {
                if (jarHasClass(jar)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean jarHasClass(Path jar) throws IOException {
        try (InputStream in = Files.newInputStream(jar);
                ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().endsWith(".class")) {
                    return true;
                }
            }
        }
        return false;
    }
}
