package ua.probe.byd;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen widget: cabin and outside temperature. Tap = refresh now. */
public class TempWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        render(ctx, mgr, ids);
        startService(ctx);
    }

    static void startService(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, TempService.class));
    }

    static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, TempWidget.class));
        if (ids != null && ids.length > 0) render(ctx, mgr, ids);
    }

    static String fmt(int v) {
        if (v == Integer.MIN_VALUE) return "—";
        return (v > 0 ? "+" : "") + v + "°";
    }

    static final int SNOW_BELOW = 5;

    /** Outside temperature with a snowflake when it is below +5 °C, e.g. "+2° ❄". */
    static String fmtOut(int v) {
        String s = fmt(v);
        if (v != Integer.MIN_VALUE && v < SNOW_BELOW) s += " \u2744";
        return s;
    }

    /** Short text, e.g. "Салон +15°  ·  Вулиця +2° ❄". */
    static String summary(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(TempService.PREFS, Context.MODE_PRIVATE);
        if (!p.contains("time")) return "Немає даних: " + p.getString("status", "…");
        String s = "Салон " + fmt(p.getInt("in", Integer.MIN_VALUE))
                + "  ·  Вулиця " + fmtOut(p.getInt("out", Integer.MIN_VALUE));
        if (isStale(p)) s += "  (застаріло)";
        return s;
    }

    private static boolean isStale(SharedPreferences p) {
        return System.currentTimeMillis() - p.getLong("time", 0) > 3 * TempService.INTERVAL_MS;
    }

    static void render(Context ctx, AppWidgetManager mgr, int[] ids) {
        SharedPreferences p = ctx.getSharedPreferences(TempService.PREFS, Context.MODE_PRIVATE);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_temps);
        rv.setTextViewText(R.id.inside, fmt(p.getInt("in", Integer.MIN_VALUE)));
        rv.setTextViewText(R.id.outside, fmtOut(p.getInt("out", Integer.MIN_VALUE)));

        String foot;
        if (!p.contains("time")) {
            foot = p.getString("status", "очікування…");
        } else {
            foot = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(p.getLong("time", 0)));
            if (isStale(p)) foot += " · " + p.getString("status", "застаріло");
        }
        rv.setTextViewText(R.id.footer, foot);

        Intent refresh = new Intent(ctx, TempService.class).setAction(TempService.ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getForegroundService(ctx, 0, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.root, pi);
        mgr.updateAppWidget(ids, rv);
    }
}
