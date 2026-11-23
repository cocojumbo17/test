package ua.probe.byd;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TemperatureTextTest {

    private static final long NOW = 1_000_000_000L;

    @Test
    public void positiveValuesGetAPlusSign() {
        assertEquals("+15°", TemperatureText.degrees(15));
    }

    @Test
    public void zeroAndNegativeValuesHaveNoPlusSign() {
        assertEquals("0°", TemperatureText.degrees(0));
        assertEquals("-3°", TemperatureText.degrees(-3));
    }

    @Test
    public void missingValueIsADash() {
        assertEquals("—", TemperatureText.degrees(null));
        assertEquals("—", TemperatureText.outside(null));
    }

    @Test
    public void snowflakeBelowFiveDegrees() {
        assertEquals("+4° ❄", TemperatureText.outside(4));
        assertEquals("-10° ❄", TemperatureText.outside(-10));
    }

    @Test
    public void noSnowflakeFromFiveDegrees() {
        assertEquals("+5°", TemperatureText.outside(5));
    }

    @Test
    public void summaryOfAFreshReading() {
        Reading fresh = new Reading(15, 2, NOW);

        assertEquals("Салон +15°  ·  Вулиця +2° ❄", TemperatureText.summary(fresh, "ok", NOW));
    }

    @Test
    public void staleReadingIsMarked() {
        Reading old = new Reading(15, 8, NOW - Reading.STALE_AFTER_MS - 1);

        assertEquals("Салон +15°  ·  Вулиця +8° …", TemperatureText.summary(old, "ok", NOW));
    }

    @Test
    public void withoutAReadingTheStatusIsShown() {
        assertEquals("Немає даних: ADB: IOException", TemperatureText.summary(null, "ADB: IOException", NOW));
    }
}
