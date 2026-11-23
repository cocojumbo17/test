package ua.probe.byd;

/**
 * The one-line protocol between the shell-side reader ({@link TempRead}) and the app:
 * {@code "TEMPS in=15 out=2"} or {@code "TEMPS error=<reason>"}.
 * Both sides use this class, so the format lives in one place.
 */
final class TempReply {

    static final String PREFIX = "TEMPS ";
    private static final String ERROR = "error=";
    private static final String INSIDE = "in=";
    private static final String OUTSIDE = "out=";

    // Plausible air temperatures. BYD error codes are far outside: -2147482648, 65535, 195 ...
    private static final int MIN_PLAUSIBLE_C = -50;
    private static final int MAX_PLAUSIBLE_C = 90;

    /** Cabin temperature in °C, or null when missing or implausible. */
    final Integer inside;
    /** Outside temperature in °C, or null when missing or implausible. */
    final Integer outside;
    /** Why reading failed, or null. */
    final String error;

    private TempReply(Integer inside, Integer outside, String error) {
        this.inside = inside;
        this.outside = outside;
        this.error = error;
    }

    static String formatValues(Object inside, Object outside) {
        return PREFIX + INSIDE + inside + " " + OUTSIDE + outside;
    }

    static String formatError(String reason) {
        return PREFIX + ERROR + reason;
    }

    static boolean isReplyLine(String line) {
        return line != null && line.startsWith(PREFIX);
    }

    static TempReply failure(String reason) {
        return new TempReply(null, null, reason);
    }

    static TempReply parse(String line) {
        int errorAt = line.indexOf(ERROR);
        if (errorAt >= 0) return failure(line.substring(errorAt + ERROR.length()));
        return new TempReply(value(line, INSIDE), value(line, OUTSIDE), null);
    }

    boolean hasValues() {
        return inside != null || outside != null;
    }

    /** Short status for the UI: "ok", the error, or that the car sent no usable values. */
    String status() {
        if (error != null) return error;
        return hasValues() ? "ok" : "немає значень";
    }

    private static Integer value(String line, String key) {
        int start = line.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) end++;
        try {
            int celsius = Integer.parseInt(line.substring(start, end));
            return isPlausible(celsius) ? celsius : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isPlausible(int celsius) {
        return celsius >= MIN_PLAUSIBLE_C && celsius <= MAX_PLAUSIBLE_C;
    }
}
