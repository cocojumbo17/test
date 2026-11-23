package ua.probe.byd;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * All stored state in one place: the last reading and the user's settings.
 * The keys are unchanged from earlier versions, so settings survive app updates.
 */
final class AppPrefs {

    static final int DEFAULT_BG_ALPHA = 204;            // 80 %
    static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;   // white
    static final int MIN_TEXT_SIZE = 14;
    static final int MAX_TEXT_SIZE = 40;
    static final int DEFAULT_TEXT_SIZE = 22;

    private static final String FILE = "temps";
    private static final String KEY_STATUS = "status";
    private static final String KEY_INSIDE = "in";
    private static final String KEY_OUTSIDE = "out";
    private static final String KEY_TIME = "time";
    private static final String KEY_NOTIFICATIONS = "notify";
    private static final String KEY_OVERLAY = "overlay";
    private static final String KEY_BG_ALPHA = "bg_alpha";
    private static final String KEY_TEXT_COLOR = "text_color";
    private static final String KEY_TEXT_SIZE = "text_size";
    private static final String KEY_X = "ox";
    private static final String KEY_Y = "oy";

    /** How a missing temperature is stored (SharedPreferences has no nullable int). */
    private static final int NO_VALUE = Integer.MIN_VALUE;
    /** Stored as the position to ask for the overlay to be centred. */
    private static final int CENTER_REQUEST = -1;

    private final SharedPreferences prefs;

    private AppPrefs(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static AppPrefs of(Context ctx) {
        return new AppPrefs(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    // ---- last reading ----

    void saveStatus(String status) {
        prefs.edit().putString(KEY_STATUS, status).apply();
    }

    void saveReading(Integer inside, Integer outside, long timeMs) {
        prefs.edit()
                .putInt(KEY_INSIDE, inside == null ? NO_VALUE : inside)
                .putInt(KEY_OUTSIDE, outside == null ? NO_VALUE : outside)
                .putLong(KEY_TIME, timeMs)
                .apply();
    }

    /** The last reading, or null before the first one. */
    Reading lastReading() {
        if (!prefs.contains(KEY_TIME)) return null;
        return new Reading(temperature(KEY_INSIDE), temperature(KEY_OUTSIDE), prefs.getLong(KEY_TIME, 0));
    }

    String status() {
        return prefs.getString(KEY_STATUS, "…");
    }

    String currentSummary() {
        return TemperatureText.summary(lastReading(), status(), System.currentTimeMillis());
    }

    private Integer temperature(String key) {
        int value = prefs.getInt(key, NO_VALUE);
        return value == NO_VALUE ? null : value;
    }

    // ---- settings ----

    boolean notificationsEnabled() { return prefs.getBoolean(KEY_NOTIFICATIONS, false); }

    void setNotificationsEnabled(boolean on) { prefs.edit().putBoolean(KEY_NOTIFICATIONS, on).apply(); }

    boolean overlayEnabled() { return prefs.getBoolean(KEY_OVERLAY, true); }

    void setOverlayEnabled(boolean on) { prefs.edit().putBoolean(KEY_OVERLAY, on).apply(); }

    int backgroundAlpha() { return prefs.getInt(KEY_BG_ALPHA, DEFAULT_BG_ALPHA); }

    void setBackgroundAlpha(int alpha) { prefs.edit().putInt(KEY_BG_ALPHA, alpha).apply(); }

    int textColor() { return prefs.getInt(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR); }

    void setTextColor(int color) { prefs.edit().putInt(KEY_TEXT_COLOR, color).apply(); }

    int textSize() { return clampTextSize(prefs.getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE)); }

    void setTextSize(int sp) { prefs.edit().putInt(KEY_TEXT_SIZE, clampTextSize(sp)).apply(); }

    private static int clampTextSize(int sp) {
        return Math.max(MIN_TEXT_SIZE, Math.min(MAX_TEXT_SIZE, sp));
    }

    // ---- overlay position ----

    int overlayX(int fallback) { return Math.max(0, prefs.getInt(KEY_X, fallback)); }

    int overlayY(int fallback) { return Math.max(0, prefs.getInt(KEY_Y, fallback)); }

    void saveOverlayPosition(int x, int y) { prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply(); }

    void requestOverlayCenter() { saveOverlayPosition(CENTER_REQUEST, CENTER_REQUEST); }

    boolean isOverlayCenterRequested() { return prefs.getInt(KEY_X, 0) == CENTER_REQUEST; }
}
