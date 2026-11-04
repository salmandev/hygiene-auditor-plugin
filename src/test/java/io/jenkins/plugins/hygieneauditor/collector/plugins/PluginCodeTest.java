package io.jenkins.plugins.hygieneauditor.collector.plugins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Meta-plugins (suites) hold no code; they exist to depend on other plugins. */
class PluginCodeTest {
    @TempDir
    Path dir;

    private void jar(String name, String... entries) throws Exception {
        Path lib = dir.resolve("WEB-INF").resolve("lib");
        Files.createDirectories(lib);
        try (OutputStream out = Files.newOutputStream(lib.resolve(name));
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String e : entries) {
                zip.putNextEntry(new ZipEntry(e));
                zip.write(1);
                zip.closeEntry();
            }
        }
    }

    @Test
    void aJarWithOnlyAManifestIsASuite() throws Exception {
        jar("workflow-aggregator.jar", "META-INF/MANIFEST.MF", "META-INF/maven/x/pom.properties");
        assertFalse(PluginCode.hasCode(dir));
    }

    @Test
    void aJarWithClassesHasCode() throws Exception {
        jar("real.jar", "META-INF/MANIFEST.MF", "io/example/Thing.class");
        assertTrue(PluginCode.hasCode(dir));
    }

    @Test
    void anyJarWithClassesCounts() throws Exception {
        jar("empty.jar", "META-INF/MANIFEST.MF");
        jar("lib-with-code.jar", "a/B.class");
        assertTrue(PluginCode.hasCode(dir));
    }

    @Test
    void looseClassesCount() throws Exception {
        Path classes = dir.resolve("WEB-INF").resolve("classes").resolve("p");
        Files.createDirectories(classes);
        Files.write(classes.resolve("C.class"), new byte[] {1});
        assertTrue(PluginCode.hasCode(dir));
    }

    @Test
    void nothingAtAllMeansNoCode() throws Exception {
        assertFalse(PluginCode.hasCode(dir));
    }
}
