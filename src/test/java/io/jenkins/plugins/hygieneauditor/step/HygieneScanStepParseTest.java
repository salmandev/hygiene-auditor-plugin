package io.jenkins.plugins.hygieneauditor.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.AbortException;
import io.jenkins.plugins.hygieneauditor.model.Category;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class HygieneScanStepParseTest {
    @Test
    void acceptsOneNamePerEntryOrSeveralInOneTextBox() throws Exception {
        assertEquals(
                EnumSet.of(Category.JOBS, Category.CREDENTIALS), HygieneScanStep.parse(List.of("jobs", "CREDENTIALS")));
        assertEquals(
                EnumSet.of(Category.JOBS, Category.CREDENTIALS), HygieneScanStep.parse(List.of("JOBS, credentials ,")));
        assertEquals(EnumSet.noneOf(Category.class), HygieneScanStep.parse(List.of()));
    }

    @Test
    void namesTheOffendingCategory() {
        AbortException e = assertThrows(AbortException.class, () -> HygieneScanStep.parse(List.of("JOBS, NOPE")));
        assertEquals(true, e.getMessage().contains("'NOPE'"));
    }
}
