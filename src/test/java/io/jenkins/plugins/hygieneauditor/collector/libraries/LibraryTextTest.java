package io.jenkins.plugins.hygieneauditor.collector.libraries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.hygieneauditor.collector.libraries.LibraryText.LibraryRef;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LibraryTextTest {

    @Test
    void libraryRequestsAreReadWithAndWithoutVersions() {
        LibraryText.Scan s = LibraryText.scan("""
                @Library('acme@v1') _
                @Library(['a', 'b@main']) _
                library 'viaStep@2'
                library("viaParen")
                library identifier: 'viaId@x', retriever: modernSCM([$class: 'GitSCMSource'])
                """);
        List<LibraryRef> libs = s.libraries();
        assertTrue(libs.contains(new LibraryRef("acme", "v1")));
        assertTrue(libs.contains(new LibraryRef("a", null)));
        assertTrue(libs.contains(new LibraryRef("b", "main")));
        assertTrue(libs.contains(new LibraryRef("viaStep", "2")));
        assertTrue(libs.contains(new LibraryRef("viaParen", null)));
        assertTrue(libs.contains(new LibraryRef("viaId", "x")));
        assertEquals(0, s.dynamicLibraryRefs());
    }

    @Test
    void computedLibraryNamesAreDynamicNotInvented() {
        LibraryText.Scan s = LibraryText.scan("""
                library(libName)
                library "${env.LIB}@main"
                @Library("dyn-${x}") _
                library identifier: libId
                """);
        assertTrue(s.libraries().isEmpty(), s.libraries().toString());
        assertEquals(3, s.dynamicLibraryRefs());
    }

    @Test
    void proseAboutLibrariesIsNotARequest() {
        LibraryText.Scan s = LibraryText.scan("// this library is great\necho 'the library of babel'\nlibrary\n");
        assertTrue(s.libraries().isEmpty());
        assertEquals(0, s.dynamicLibraryRefs());
    }

    @Test
    void wordsQualifiedNamesAndWildcardImports() {
        LibraryText.Scan s = LibraryText.scan("""
                import org.acme.Util
                import org.acme.tools.*
                import static org.acme.Consts.LIMIT
                def x = new org.other.Thing()
                deploy('prod')
                """);
        assertTrue(s.identifiers().containsAll(List.of("deploy", "Util", "Thing", "x")));
        assertTrue(s.qualifiedNames().containsAll(List.of("org.acme.Util", "org.acme.Consts", "org.other.Thing")));
        assertEquals(java.util.Set.of("org.acme.tools"), s.wildcardPackages());
        assertFalse(s.qualifiedNames().contains("org.acme.tools"), "a wildcard is not a class");
    }

    @Test
    void aLibraryNameIsNotAUseOfAThingWithTheSameName() {
        LibraryText.Scan s = LibraryText.scan("@Library('evil@main') _\nlibrary 'other@1'\nok()\n");
        assertFalse(s.identifiers().contains("evil"), "named only as a library");
        assertFalse(s.identifiers().contains("other"));
        assertTrue(s.identifiers().contains("ok"));
        assertTrue(
                LibraryText.scan("@Library('evil@main') _\nevil()\n")
                        .identifiers()
                        .contains("evil"),
                "but really called");
        assertEquals(
                2,
                LibraryText.scan("@Library('evil@main') _\nlibrary 'other@1'\n")
                        .libraries()
                        .size(),
                "still recorded as requests");
    }

    @Test
    void packageNamesAreNotUsesOfSameNamedThings() {
        LibraryText.Scan s = LibraryText.scan(
                "package org.evil\nimport org.acme.tools.*\nimport org.acme.Util\nnew org.other.Thing()\nacme()\n");
        assertFalse(s.identifiers().contains("evil"), "package statement");
        assertFalse(s.identifiers().contains("org"));
        assertFalse(s.identifiers().contains("tools"), "wildcard import");
        assertFalse(s.identifiers().contains("other"), "qualifier of a class name");
        assertTrue(
                s.identifiers().containsAll(List.of("Util", "Thing", "acme")),
                "class names and real calls still count");
        assertTrue(s.qualifiedNames().contains("org.other.Thing"), "the qualified form is kept for class matching");
    }

    @Test
    void dynamicInvocationMarkersAreCounted() {
        assertEquals(0, LibraryText.scan("deploy('x')").dynamicCallMarkers());
        LibraryText.Scan s = LibraryText.scan("""
                evaluate("deploy()")
                this."${name}"()
                this.'dyn'()
                Class.forName('x').newInstance()
                "${n}"(1)
                """);
        assertTrue(s.dynamicCallMarkers() >= 5, "" + s.dynamicCallMarkers());
    }

    @Test
    void oversizedAndPathologicalInputIsBounded() {
        assertTrue(LibraryText.scan("x".repeat(LibraryText.MAX_TEXT + 1))
                .identifiers()
                .isEmpty());
        String evil = "@Library(" + "'a".repeat(50_000) + "\nlibrary(" + " ".repeat(100_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> LibraryText.scan(evil));
    }

    @Test
    void recordedBuildDirectoryNamesCannotSteerThePath() {
        Path build = Path.of("/builds/1");
        assertEquals(build.resolve("libs").resolve("abc123"), LibraryInventory.checkoutDir(build, "abc123"));
        for (String bad : List.of("../../etc", "a/b", "..", "", "x\u0000y", "a b", "/etc")) {
            assertNull(LibraryInventory.checkoutDir(build, bad), bad);
        }
        assertNull(LibraryInventory.checkoutDir(build, null));
    }
}
