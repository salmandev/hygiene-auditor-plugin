package io.jenkins.plugins.hygieneauditor.collector.libraries;

import hudson.plugins.git.GitSCM;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import jenkins.plugins.git.GitSCMSource;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration;
import org.jenkinsci.plugins.workflow.libs.SCMSourceRetriever;

/** Builds real git repositories and library configurations for tests. */
final class LibraryFixture {
    private LibraryFixture() {}

    /** Git refuses to clone a local path unless told otherwise; the flag is read once at class load. */
    static void allowLocalCheckout() throws ReflectiveOperationException {
        var field = GitSCM.class.getDeclaredField("ALLOW_LOCAL_CHECKOUT");
        field.setAccessible(true);
        field.setBoolean(null, true);
    }

    /** Creates a git repository on branch {@code main} containing the given files (relative path to content). */
    static Path repo(Path dir, Map<String, String> files) throws Exception {
        Files.createDirectories(dir);
        for (var e : files.entrySet()) {
            Path f = dir.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
        git(dir, "init", "-q", "-b", "main");
        git(dir, "-c", "user.email=a@b.c", "-c", "user.name=t", "add", "-A");
        git(dir, "-c", "user.email=a@b.c", "-c", "user.name=t", "commit", "-q", "-m", "init");
        return dir;
    }

    static LibraryConfiguration config(String name, Path repo) {
        LibraryConfiguration c =
                new LibraryConfiguration(name, new SCMSourceRetriever(new GitSCMSource(repo.toString())));
        c.setDefaultVersion("main");
        return c;
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + "\n" + out);
        }
    }

    static CpsFlowDefinition script(String s) throws Exception {
        return new CpsFlowDefinition(s, true);
    }
}
