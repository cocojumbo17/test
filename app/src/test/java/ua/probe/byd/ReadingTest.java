package ua.probe.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReadingTest {

    @Test
    public void readingIsFreshUpToThreeIntervals() {
        Reading reading = new Reading(15, 2, 0);

        assertFalse(reading.isStale(Reading.STALE_AFTER_MS));
    }

    @Test
    public void readingIsStaleAfterThreeIntervals() {
        Reading reading = new Reading(15, 2, 0);

        assertTrue(reading.isStale(Reading.STALE_AFTER_MS + 1));
    }
}
