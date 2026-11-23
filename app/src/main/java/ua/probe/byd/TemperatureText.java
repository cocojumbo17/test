package ua.probe.byd;

/** How temperatures are written for the user: "+15°", "+2° ❄", "Салон +15°  ·  Вулиця +2° ❄". */
final class TemperatureText {

    /** A snowflake is shown next to the outside temperature below this value. */
    static final int SNOW_BELOW_C = 5;

    private static final String NO_VALUE = "—";
    private static final String SNOWFLAKE = " ❄";
    private static final String STALE_MARK = " …";

    private TemperatureText() { }

    static String degrees(Integer celsius) {
        if (celsius == null) return NO_VALUE;
        return (celsius > 0 ? "+" : "") + celsius + "°";
    }

    static String outside(Integer celsius) {
        boolean freezingRisk = celsius != null && celsius < SNOW_BELOW_C;
        return degrees(celsius) + (freezingRisk ? SNOWFLAKE : "");
    }

    /** One line for the overlay, notification and main screen. */
    static String summary(Reading reading, String status, long nowMs) {
        if (reading == null) return "Немає даних: " + status;
        String text = "Салон " + degrees(reading.inside) + "  ·  Вулиця " + outside(reading.outside);
        return reading.isStale(nowMs) ? text + STALE_MARK : text;
    }
}
