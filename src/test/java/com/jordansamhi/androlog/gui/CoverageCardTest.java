package com.jordansamhi.androlog.gui;
import org.junit.Test;
import java.awt.Color;
import static org.junit.Assert.*;
public class CoverageCardTest {
    @Test public void statesSeparateRawObservationsFromMatchedCoverage() {
        CoverageCard card = new CoverageCard("Methods", Color.BLUE);
        assertTrue(card.getAccessibleContext().getAccessibleName().contains("no log loaded"));
        card.showObservation(12, false);
        assertTrue(card.getAccessibleContext().getAccessibleName().contains("12, Observed"));
        card.showCoverage(new CoverageJob.Metric(1, 4));
        assertTrue(card.getAccessibleContext().getAccessibleName().contains("25.0%"));
        assertTrue(card.getAccessibleContext().getAccessibleName().contains("1 of 4 observed"));
        card.showCoverage(new CoverageJob.Metric(0, 0));
        assertTrue(card.getAccessibleContext().getAccessibleName().contains("Nothing to cover"));
    }
}
