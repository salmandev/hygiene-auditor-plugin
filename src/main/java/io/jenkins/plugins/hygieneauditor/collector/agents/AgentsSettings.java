package io.jenkins.plugins.hygieneauditor.collector.agents;

/** @param offlineDays agents offline for at least this long are reported */
public record AgentsSettings(int offlineDays) {
    public AgentsSettings {
        if (offlineDays < 1) {
            throw new IllegalArgumentException("offlineDays must be positive");
        }
    }

    public static AgentsSettings defaults() {
        return new AgentsSettings(30);
    }
}
