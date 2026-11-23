package ua.probe.byd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * The service's notification. With notifications enabled it shows the temperatures and is
 * re-posted only when they change; otherwise it is the quiet one Android requires for a
 * foreground service (without it the system would stop the service).
 */
final class TempNotifier {

    static final int ID = 1;

    private static final String CHANNEL_TEMPS = "temps";
    private static final String CHANNEL_QUIET = "background";
    private static final String QUIET_TEXT = "BYD Temp працює у фоні";
    private static final String STARTING_TEXT = "Температура: зчитую…";

    private final Context ctx;
    private final NotificationManager manager;
    private String shownText;

    TempNotifier(Context ctx) {
        this.ctx = ctx;
        this.manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(
                new NotificationChannel(CHANNEL_TEMPS, "Температура", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(
                new NotificationChannel(CHANNEL_QUIET, "Робота у фоні", NotificationManager.IMPORTANCE_MIN));
    }

    /** The notification to start the foreground service with. */
    Notification initial() {
        shownText = AppPrefs.of(ctx).notificationsEnabled() ? STARTING_TEXT : QUIET_TEXT;
        return build(shownText);
    }

    /** Re-posts the notification if its text changed. Safe to call from any thread. */
    synchronized void update() {
        String text = currentText();
        if (text.equals(shownText)) return;
        shownText = text;
        manager.notify(ID, build(text));
    }

    private String currentText() {
        AppPrefs prefs = AppPrefs.of(ctx);
        return prefs.notificationsEnabled() ? prefs.currentSummary() : QUIET_TEXT;
    }

    private Notification build(String text) {
        boolean temps = AppPrefs.of(ctx).notificationsEnabled();
        PendingIntent openApp = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(ctx, temps ? CHANNEL_TEMPS : CHANNEL_QUIET)
                .setSmallIcon(R.drawable.ic_stat_thermo)
                .setContentTitle(temps ? "BYD температура" : "BYD Temp")
                .setContentText(text)
                .setContentIntent(openApp)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }
}
