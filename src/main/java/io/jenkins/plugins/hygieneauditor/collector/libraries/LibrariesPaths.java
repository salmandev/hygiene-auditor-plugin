package io.jenkins.plugins.hygieneauditor.collector.libraries;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class LibrariesPaths {
    private LibrariesPaths() {}

    static boolean isGroovy(Path p) {
        Path n = p.getFileName();
        return n != null && n.toString().endsWith(".groovy") && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
    }

    static String stem(Path p) {
        Path n = p.getFileName();
        String s = n == null ? "" : n.toString();
        return s.endsWith(".groovy") ? s.substring(0, s.length() - ".groovy".length()) : s;
    }
}
