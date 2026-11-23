package ua.probe.byd;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen widget (for launchers that support it): cabin and outside temperature. Tap = refresh. */
public class TempWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager manager, int[] ids) {
        render(ctx, manager, ids);
        TempService.start(ctx);
    }

    static void updateAll(Context ctx) {
        AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
        int[] ids = manager.getAppWidgetIds(new ComponentName(ctx, TempWidget.class));
        if (ids != null && ids.length > 0) render(ctx, manager, ids);
    }

    private static void render(Context ctx, AppWidgetManager manager, int[] ids) {
        AppPrefs prefs = AppPrefs.of(ctx);
        Reading reading = prefs.lastReading();
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_temps);
        views.setTextViewText(R.id.inside, TemperatureText.degrees(reading == null ? null : reading.inside));
        views.setTextViewText(R.id.outside, TemperatureText.outside(reading == null ? null : reading.outside));
        views.setTextViewText(R.id.footer, footer(reading, prefs.status()));
        views.setOnClickPendingIntent(R.id.root, refreshIntent(ctx));
        manager.updateAppWidget(ids, views);
    }

    /** Time of the last reading, plus the status when it is out of date. */
    private static String footer(Reading reading, String status) {
        if (reading == null) return status;
        String time = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(reading.timeMs));
        return reading.isStale(System.currentTimeMillis()) ? time + " · " + status : time;
    }

    private static PendingIntent refreshIntent(Context ctx) {
        Intent refresh = new Intent(ctx, TempService.class).setAction(TempService.ACTION_REFRESH);
        return PendingIntent.getForegroundService(ctx, 0, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
