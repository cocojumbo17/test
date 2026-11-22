package ua.probe.byd;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Small floating label over all apps: "Салон +15° · Вулиця +2°".
 * Drag to move (position is remembered), tap to refresh now.
 * Must be used on the main thread.
 */
public class TempOverlay {

    private final Context ctx;
    private final Runnable onTap;
    private WindowManager wm;
    private TextView view;
    private WindowManager.LayoutParams lp;

    public TempOverlay(Context ctx, Runnable onTap) {
        this.ctx = ctx;
        this.onTap = onTap;
    }

    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(TempService.PREFS, Context.MODE_PRIVATE).getBoolean("overlay", true);
    }

    static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(TempService.PREFS, Context.MODE_PRIVATE).edit().putBoolean("overlay", on).apply();
    }

    /** Shows, updates or hides the label according to settings and permission. */
    public void refresh() {
        if (!enabled(ctx) || !Settings.canDrawOverlays(ctx)) {
            hide();
            return;
        }
        if (view == null) show();
        if (view != null) view.setText(TempWidget.summary(ctx));
    }

    private void show() {
        try {
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            view = new TextView(ctx);
            view.setTextSize(22);
            view.setTextColor(0xFFFFFFFF);
            view.setPadding(28, 14, 28, 14);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xCC1E2329);
            bg.setCornerRadius(36);
            view.setBackground(bg);

            SharedPreferences p = ctx.getSharedPreferences(TempService.PREFS, Context.MODE_PRIVATE);
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = p.getInt("ox", 40);
            lp.y = p.getInt("oy", 120);

            view.setOnTouchListener(new View.OnTouchListener() {
                float downX, downY;
                int startX, startY;
                boolean moved;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX(); downY = e.getRawY();
                            startX = lp.x; startY = lp.y; moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            int dx = (int) (e.getRawX() - downX), dy = (int) (e.getRawY() - downY);
                            if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true;
                            lp.x = startX + dx; lp.y = startY + dy;
                            wm.updateViewLayout(view, lp);
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (moved) {
                                ctx.getSharedPreferences(TempService.PREFS, Context.MODE_PRIVATE).edit()
                                        .putInt("ox", lp.x).putInt("oy", lp.y).apply();
                            } else if (onTap != null) {
                                onTap.run();
                            }
                            return true;
                    }
                    return false;
                }
            });
            wm.addView(view, lp);
        } catch (Exception e) {
            view = null;
        }
    }

    public void hide() {
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
        }
        view = null;
    }
}
