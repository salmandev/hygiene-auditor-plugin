package io.jenkins.plugins.hygieneauditor.scan;

import io.jenkins.plugins.hygieneauditor.collector.CollectorResult;
import io.jenkins.plugins.hygieneauditor.collector.HygieneCollector;
import io.jenkins.plugins.hygieneauditor.collector.ScanContext;
import io.jenkins.plugins.hygieneauditor.model.Category;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Evidence;
import io.jenkins.plugins.hygieneauditor.model.FindingCandidate;
import io.jenkins.plugins.hygieneauditor.model.Severity;
import io.jenkins.plugins.hygieneauditor.model.Subject;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** A controllable collector for engine tests. Register by subclassing with {@code @TestExtension}. */
public final class TestStubs {
    public interface Body {
        void run(ScanContext ctx) throws Exception;
    }

    public static final AtomicReference<Body> BODY = new AtomicReference<>(ctx -> {});

    private TestStubs() {}

    public static FindingCandidate highPluginFinding() {
        return new FindingCandidate(
                "plugin.stub",
                Category.PLUGINS,
                Subject.of("plugin", "stub-plugin", null),
                Severity.HIGH,
                Confidence.HIGH,
                List.of(new Evidence("stub", "synthetic", 80)),
                "none");
    }

    public static FindingCandidate pluginFinding(String name, Severity severity) {
        return new FindingCandidate(
                "plugin.stub",
                Category.PLUGINS,
                Subject.of("plugin", name, null),
                severity,
                Confidence.HIGH,
                List.of(new Evidence("stub", "evidence text for " + name, 80)),
                "none");
    }

    public abstract static class StubCollector extends HygieneCollector {
        @Override
        public String getId() {
            return "test-stub";
        }

        @Override
        public Category getCategory() {
            return Category.PLUGINS;
        }

        @Override
        public String getDisplayName() {
            return "stub";
        }

        @Override
        public CollectorResult collect(ScanContext ctx) throws InterruptedException {
            try {
                BODY.get().run(ctx);
            } catch (InterruptedException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return CollectorResult.complete();
        }
    }
}
