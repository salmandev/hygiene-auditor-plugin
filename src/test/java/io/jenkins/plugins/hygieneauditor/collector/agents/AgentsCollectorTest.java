package io.jenkins.plugins.hygieneauditor.collector.agents;

import static io.jenkins.plugins.hygieneauditor.collector.CollectorTestSupport.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Computer;
import hudson.slaves.DumbSlave;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import io.jenkins.plugins.hygieneauditor.collector.CollectorTestSupport;
import io.jenkins.plugins.hygieneauditor.model.Confidence;
import io.jenkins.plugins.hygieneauditor.model.Finding;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AgentsCollectorTest {
    @TempDir
    Path dir;

    JenkinsRule j;
    CollectorTestSupport t;
    final AgentsCollector collector = new AgentsCollector();

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        t = new CollectorTestSupport(dir);
    }

    private DumbSlave offlineAgent(String name) throws Exception {
        DumbSlave s = j.createSlave(name, null, null);
        Computer c = s.toComputer();
        c.setTemporarilyOffline(true, new OfflineCause.ByCLI("maintenance"));
        return s;
    }

    @Test
    void builtInNodeAndOnlineAgentsAreNeverReported() throws Exception {
        j.createOnlineSlave();
        assertTrue(t.scan(collector, 400).isEmpty());
    }

    @Test
    void recentlyOfflineAgentIsNotReported() throws Exception {
        offlineAgent("brief");
        assertTrue(t.scan(collector, 5).isEmpty());
    }

    @Test
    void longOfflineAgentIsReportedWithGrowingConfidence() throws Exception {
        offlineAgent("old");
        Finding medium = find(t.scan(collector, 40), "agent.offline", "old").orElseThrow();
        assertEquals(Confidence.MEDIUM, medium.confidence());
        assertTrue(
                medium.evidence().stream().anyMatch(e -> e.text().contains("restarts when the controller restarts")));
        Finding high = find(t.scan(collector, 100), "agent.offline", "old").orElseThrow();
        assertEquals(Confidence.HIGH, high.confidence());
    }

    @Test
    void onDemandAgentsAreFlaggedWithLowConfidence() throws Exception {
        DumbSlave s = offlineAgent("demand");
        s.setRetentionStrategy(new RetentionStrategy.Demand(1, 1));
        j.jenkins.updateNode(s);
        s.toComputer().setTemporarilyOffline(true, new OfflineCause.ByCLI("idle"));
        List<Finding> found = t.scan(collector, 100);
        Finding f = find(found, "agent.offline", "demand").orElseThrow();
        assertEquals(Confidence.LOW, f.confidence());
        assertTrue(f.evidence().stream().anyMatch(e -> e.text().contains("on-demand")));
    }

    @Test
    void findingResolvesWhenAgentComesBack() throws Exception {
        DumbSlave s = offlineAgent("returns");
        assertTrue(find(t.scan(collector, 60), "agent.offline", "returns").isPresent());
        s.toComputer().setTemporarilyOffline(false, null);
        s.toComputer().waitUntilOnline();
        assertFalse(find(t.scan(collector, 60), "agent.offline", "returns").isPresent());
    }
}
