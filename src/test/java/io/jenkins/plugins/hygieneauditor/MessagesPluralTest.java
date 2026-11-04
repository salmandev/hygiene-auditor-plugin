package io.jenkins.plugins.hygieneauditor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MessagesPluralTest {
    @Test
    void countsReadNaturallyInSingularAndPlural() {
        assertEquals("1 unused variable", Messages.Card_UnusedVariables(1));
        assertEquals("2 unused variables", Messages.Card_UnusedVariables(2));
        assertEquals("0 unused variables", Messages.Card_UnusedVariables(0));
        assertEquals("3 high confidence", Messages.Card_HighConfidence(3));
        assertEquals("offline over 30 days", Messages.Card_OfflineOver(30));
    }
}
