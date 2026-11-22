package ua.probe.byd;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Floating label over all apps: "Салон +15° · Вулиця +2° ❄".
 * Drag to move (kept inside the screen; the status-bar strip is allowed), tap to refresh.
 * Background opacity and text colour come from settings. Main thread only.
 */
public class TempOverlay {

    static final int DEFAULT_BG_ALPHA = 204;          // 80 %
    static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;  // white
    private static final int BG_RGB = 0x1E2329;
    static final int MIN_TEXT_SIZE = 14, MAX_TEXT_SIZE = 40, DEFAULT_TEXT_SIZE = 22;

    private final Context ctx;
    private final Runnable onTap;
    private WindowManager wm;
    private TextView view;
    private WindowManager.LayoutParams lp;
    private GradientDrawable bg;

    public TempOverlay(Context ctx, Runnable onTap) {
        this.ctx = ctx;
        this.onTap = onTap;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(TempService.PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context ctx) { return prefs(ctx).getBoolean("overlay", true); }

    static void setEnabled(Context ctx, boolean on) { prefs(ctx).edit().putBoolean("overlay", on).apply(); }

    static int bgAlpha(Context ctx) { return prefs(ctx).getInt("bg_alpha", DEFAULT_BG_ALPHA); }

    static int textColor(Context ctx) { return prefs(ctx).getInt("text_color", DEFAULT_TEXT_COLOR); }

    static int textSize(Context ctx) {
        int sp = prefs(ctx).getInt("text_size", DEFAULT_TEXT_SIZE);
        return Math.max(MIN_TEXT_SIZE, Math.min(MAX_TEXT_SIZE, sp));
    }

    /** Asks for the label to be centred the next time it is shown or refreshed. */
    static void requestCenter(Context ctx) { prefs(ctx).edit().putInt("ox", -1).putInt("oy", -1).apply(); }

    /** Shows, updates or hides the label according to settings and permission. */
    public void refresh() {
        if (!enabled(ctx) || !Settings.canDrawOverlays(ctx)) {
            hide();
            return;
        }
        if (view == null) show();
        if (view == null) return;
        view.setText(TempWidget.summary(ctx));
        applyStyle();
        if (prefs(ctx).getInt("ox", 0) < 0) view.post(this::center);
    }

    public void applyStyle() {
        if (view == null) return;
        view.setTextColor(textColor(ctx));
        int sp = textSize(ctx);
        view.setTextSize(sp);
        // padding and corners grow with the font so the label keeps its proportions
        int padH = Math.round(sp * 1.3f), padV = Math.round(sp * 0.65f);
        view.setPadding(padH, padV, padH, padV);
        bg.setCornerRadius(sp * 1.6f);
        bg.setColor((bgAlpha(ctx) << 24) | BG_RGB);
        view.setBackground(bg);
        view.post(this::update);
    }

    /** Puts the label in the middle of the screen and remembers that position. */
    public void center() {
        if (view == null) return;
        int[] s = screen();
        lp.x = Math.max(0, (s[0] - view.getWidth()) / 2);
        lp.y = Math.max(0, (s[1] - view.getHeight()) / 2);
        update();
        save();
    }

    private void show() {
        try {
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            view = new TextView(ctx);
            bg = new GradientDrawable();

            SharedPreferences p = prefs(ctx);
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // LAYOUT_IN_SCREEN: coordinates are from the very top, so the status-bar strip is reachable
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Math.max(0, p.getInt("ox", 40));
            lp.y = Math.max(0, p.getInt("oy", 120));

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
                            update();
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (moved) save();
                            else if (onTap != null) onTap.run();
                            return true;
                    }
                    return false;
                }
            });
            applyStyle();
            wm.addView(view, lp);
        } catch (Exception e) {
            view = null;
        }
    }

    /** Clamps the label inside the screen and applies the position. */
    private void update() {
        int[] s = screen();
        int w = view.getWidth(), h = view.getHeight();
        lp.x = Math.max(0, Math.min(lp.x, s[0] - w));
        lp.y = Math.max(0, Math.min(lp.y, s[1] - h));
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
    }

    private void save() {
        prefs(ctx).edit().putInt("ox", lp.x).putInt("oy", lp.y).apply();
    }

    /** Full physical size of the main screen, status bar included. */
    @SuppressWarnings("deprecation")
    private int[] screen() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    public void hide() {
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
        }
        view = null;
    }
}
