package ua.probe.byd;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

/**
 * Foreground service: reads cabin and outside temperature from the car on a schedule and
 * publishes them to the floating label, the widget and the notification.
 * Reading happens on a worker thread; the label is touched only on the main thread.
 */
public class TempService extends Service {

    public static final String ACTION_REFRESH = "ua.probe.byd.REFRESH";
    public static final String ACTION_STYLE = "ua.probe.byd.STYLE";
    public static final String ACTION_CENTER = "ua.probe.byd.CENTER";

    private HandlerThread worker;
    private Handler workerHandler;
    private Handler mainHandler;
    private CarShell carShell;
    private TempOverlay overlay;
    private TempNotifier notifier;
    private boolean overlayPermissionGranted;
    private int consecutiveFailures;

    private final Runnable readLoop = new Runnable() {
        @Override public void run() {
            boolean fresh = readAndPublish();
            consecutiveFailures = fresh ? 0 : consecutiveFailures + 1;
            workerHandler.postDelayed(this, RetryPolicy.nextDelayMs(consecutiveFailures));
        }
    };

    static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, TempService.class));
    }

    static void send(Context ctx, String action) {
        ctx.startForegroundService(new Intent(ctx, TempService.class).setAction(action));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notifier = new TempNotifier(this);
        startForeground(TempNotifier.ID, notifier.initial());
        carShell = new CarShell(this);
        mainHandler = new Handler(Looper.getMainLooper());
        worker = new HandlerThread("temps");
        worker.start();
        workerHandler = new Handler(worker.getLooper());
        overlay = new TempOverlay(this, this::readNow);
        refreshOverlay();
        workerHandler.post(readLoop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CENTER.equals(action)) AppPrefs.of(this).requestOverlayCenter();
        if (ACTION_REFRESH.equals(action)) readNow();
        refreshOverlay();
        notifier.update();
        return START_STICKY;
    }

    private void readNow() {
        workerHandler.removeCallbacks(readLoop);
        workerHandler.post(readLoop);
    }

    /** Worker thread. Returns true when fresh values arrived. */
    private boolean readAndPublish() {
        grantOverlayPermissionOnce();
        TempReply reply = carShell.readTemperatures();
        store(reply);
        publish();
        return reply.hasValues();
    }

    /** Retried on every read until it succeeds, because ADB may not be ready right after start. */
    private void grantOverlayPermissionOnce() {
        if (overlayPermissionGranted || Settings.canDrawOverlays(this)) return;
        overlayPermissionGranted = carShell.grantOverlayPermission();
    }

    private void store(TempReply reply) {
        AppPrefs prefs = AppPrefs.of(this);
        prefs.saveStatus(reply.status());
        if (reply.hasValues()) prefs.saveReading(reply.inside, reply.outside, System.currentTimeMillis());
    }

    private void publish() {
        TempWidget.updateAll(this);
        refreshOverlay();
        notifier.update();
    }

    private void refreshOverlay() {
        mainHandler.post(overlay::refresh);
    }

    @Override
    public void onDestroy() {
        workerHandler.removeCallbacksAndMessages(null);
        overlay.hide();
        carShell.close();
        worker.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
