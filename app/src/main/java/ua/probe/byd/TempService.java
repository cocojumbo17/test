package ua.probe.byd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.security.KeyPair;

/**
 * Foreground service: every 30 s reads cabin/outside temperature through the car's own ADB
 * (as the shell user) and pushes it to the floating label, the widget and the notification.
 */
public class TempService extends Service {

    public static final String ACTION_REFRESH = "ua.probe.byd.REFRESH";
    public static final String ACTION_STYLE = "ua.probe.byd.STYLE";
    public static final String ACTION_CENTER = "ua.probe.byd.CENTER";
    static final String PREFS = "temps";
    static final long INTERVAL_MS = 30_000;
    private static final String CHANNEL = "temps";            // temperatures, shown when the option is on
    private static final String CHANNEL_QUIET = "background"; // minimal, required for a foreground service
    private static final int NOTIF_ID = 1;

    private HandlerThread thread;
    private Handler handler;
    private AdbClient adb;
    private Handler main;
    private TempOverlay overlay;
    private boolean overlayGrantTried;
    private String lastNotif;   // what the notification currently shows, to post only on change

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            readOnce();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Температура", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_QUIET, "Робота у фоні", NotificationManager.IMPORTANCE_MIN));
        lastNotif = notificationsOn(this) ? "Температура: зчитую…" : QUIET_TEXT;
        startForeground(NOTIF_ID, buildNotification(lastNotif));
        thread = new HandlerThread("temps");
        thread.start();
        handler = new Handler(thread.getLooper());
        main = new Handler(Looper.getMainLooper());
        overlay = new TempOverlay(this, () -> {
            handler.removeCallbacks(tick);
            handler.post(tick);
        });
        main.post(overlay::refresh);
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (main != null && overlay != null) {
            if (ACTION_CENTER.equals(action)) TempOverlay.requestCenter(this);
            main.post(overlay::refresh);
        }
        if (ACTION_STYLE.equals(action)) updateNotification();
        if (ACTION_REFRESH.equals(action) && handler != null) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
        return START_STICKY;
    }

    private void readOnce() {
        String status;
        Integer in = null, out = null;
        try {
            if (adb == null) {
                KeyPair kp = AdbClient.loadOrCreateKeys(getFilesDir());
                adb = AdbClient.connect("127.0.0.1", 5555, kp, 60_000, s -> { });
            }
            // One-time: let the app draw the floating label (car has no settings screen for it).
            if (!overlayGrantTried && !Settings.canDrawOverlays(this)) {
                overlayGrantTried = true;
                adb.shell("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow", 10_000, l -> { });
            }
            final String[] line = new String[1];
            String apk = getApplicationInfo().sourceDir;
            adb.shell("CLASSPATH=" + apk + " app_process /system/bin ua.probe.byd.ShellMain read 2>&1",
                    20_000, l -> { if (l.startsWith("TEMPS ")) line[0] = l; });
            if (line[0] == null) {
                status = "немає відповіді";
            } else if (line[0].contains("error=")) {
                status = line[0].substring(line[0].indexOf("error=") + 6);
            } else {
                in = parse(line[0], "in=");
                out = parse(line[0], "out=");
                status = "ok";
            }
        } catch (Exception e) {
            status = "ADB: " + e.getClass().getSimpleName();
            if (adb != null) adb.close();
            adb = null;
        }

        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        ed.putString("status", status);
        if (in != null || out != null) {
            ed.putInt("in", in == null ? Integer.MIN_VALUE : in);
            ed.putInt("out", out == null ? Integer.MIN_VALUE : out);
            ed.putLong("time", System.currentTimeMillis());
        }
        ed.apply();

        TempWidget.updateAll(this);
        if (main != null && overlay != null) main.post(overlay::refresh);
        updateNotification();
    }

    private static final String QUIET_TEXT = "BYD Temp працює у фоні";

    static boolean notificationsOn(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("notify", false);
    }

    /** Posts the temperature notification only when its text changes; otherwise keeps the quiet one. */
    private synchronized void updateNotification() {
        String text = notificationsOn(this) ? TempWidget.summary(this) : QUIET_TEXT;
        if (text.equals(lastNotif)) return;
        lastNotif = text;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    /** Parses "key=<int>"; returns null for missing values or BYD error codes. */
    static Integer parse(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return null;
        int j = i + key.length();
        int k = j;
        while (k < line.length() && (Character.isDigit(line.charAt(k)) || line.charAt(k) == '-')) k++;
        try {
            int v = Integer.parseInt(line.substring(j, k));
            // Plausible air temperatures only; BYD error codes are huge negatives, 65535, 195, etc.
            return (v >= -50 && v <= 90) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean on = notificationsOn(this);
        return new Notification.Builder(this, on ? CHANNEL : CHANNEL_QUIET)
                .setSmallIcon(R.drawable.ic_stat_thermo)
                .setContentTitle(on ? "BYD температура" : "BYD Temp")
                .setOnlyAlertOnce(true)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (overlay != null) overlay.hide();
        if (adb != null) adb.close();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
