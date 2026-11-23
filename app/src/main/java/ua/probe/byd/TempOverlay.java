package ua.probe.byd;

import android.content.Context;
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
 * Floating label over all apps: "Салон +15°  ·  Вулиця +2° ❄".
 * Drag to move (kept inside the screen; the status-bar strip is allowed), tap to refresh.
 * Style comes from {@link AppPrefs}. Main thread only.
 */
final class TempOverlay {

    private static final int BACKGROUND_RGB = 0x1E2329;
    private static final int DEFAULT_X = 40;
    private static final int DEFAULT_Y = 120;
    // Padding and corners grow with the font so the label keeps its proportions.
    private static final float PADDING_H_PER_SP = 1.3f;
    private static final float PADDING_V_PER_SP = 0.65f;
    private static final float CORNER_PER_SP = 1.6f;

    private final Context ctx;
    private final Runnable onTap;
    private WindowManager windowManager;
    private TextView label;
    private WindowManager.LayoutParams params;
    private GradientDrawable background;

    TempOverlay(Context ctx, Runnable onTap) {
        this.ctx = ctx;
        this.onTap = onTap;
    }

    /** Shows, updates or hides the label according to the settings and the permission. */
    void refresh() {
        AppPrefs prefs = AppPrefs.of(ctx);
        if (!prefs.overlayEnabled() || !Settings.canDrawOverlays(ctx)) {
            hide();
            return;
        }
        if (label == null) show(prefs);
        if (label == null) return;
        label.setText(prefs.currentSummary());
        applyStyle(prefs);
        if (prefs.isOverlayCenterRequested()) label.post(this::moveToCenter);
    }

    void hide() {
        if (label != null) {
            try { windowManager.removeView(label); } catch (Exception ignored) { }
        }
        label = null;
    }

    private void show(AppPrefs prefs) {
        try {
            windowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            label = new TextView(ctx);
            background = new GradientDrawable();
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // LAYOUT_IN_SCREEN: coordinates start at the very top, so the status-bar strip is reachable
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = prefs.overlayX(DEFAULT_X);
            params.y = prefs.overlayY(DEFAULT_Y);
            label.setOnTouchListener(new DragOrTap());
            applyStyle(prefs);
            windowManager.addView(label, params);
        } catch (Exception e) {
            label = null;
        }
    }

    private void applyStyle(AppPrefs prefs) {
        int sp = prefs.textSize();
        label.setTextSize(sp);
        label.setTextColor(prefs.textColor());
        int horizontal = Math.round(sp * PADDING_H_PER_SP);
        int vertical = Math.round(sp * PADDING_V_PER_SP);
        label.setPadding(horizontal, vertical, horizontal, vertical);
        background.setCornerRadius(sp * CORNER_PER_SP);
        background.setColor((prefs.backgroundAlpha() << 24) | BACKGROUND_RGB);
        label.setBackground(background);
        label.post(this::keepInsideScreen);   // a bigger font may push the label past the edge
    }

    private void moveToCenter() {
        if (label == null) return;
        int[] screen = screenSize();
        params.x = (screen[0] - label.getWidth()) / 2;
        params.y = (screen[1] - label.getHeight()) / 2;
        keepInsideScreen();
        savePosition();
    }

    private void keepInsideScreen() {
        if (label == null) return;
        int[] screen = screenSize();
        params.x = Math.max(0, Math.min(params.x, screen[0] - label.getWidth()));
        params.y = Math.max(0, Math.min(params.y, screen[1] - label.getHeight()));
        try { windowManager.updateViewLayout(label, params); } catch (Exception ignored) { }
    }

    private void savePosition() {
        AppPrefs.of(ctx).saveOverlayPosition(params.x, params.y);
    }

    /** Full physical size of the main screen, status bar included. */
    @SuppressWarnings("deprecation")
    private int[] screenSize() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new int[]{metrics.widthPixels, metrics.heightPixels};
    }

    /** A short touch is a tap (refresh); moving the finger further drags the label. */
    private final class DragOrTap implements View.OnTouchListener {
        private static final int TAP_SLOP_PX = 12;
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = params.x;
                    startY = params.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - downX);
                    int dy = (int) (e.getRawY() - downY);
                    if (Math.abs(dx) > TAP_SLOP_PX || Math.abs(dy) > TAP_SLOP_PX) dragging = true;
                    params.x = startX + dx;
                    params.y = startY + dy;
                    keepInsideScreen();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) savePosition();
                    else onTap.run();
                    return true;
                default:
                    return false;
            }
        }
    }
}
