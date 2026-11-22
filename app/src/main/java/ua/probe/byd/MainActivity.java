package ua.probe.byd;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Main screen: live temperatures and the floating label settings. */
public class MainActivity extends Activity {

    private static final int[] COLORS = {
        0xFFFFFFFF, 0xFFFFEB3B, 0xFFFFA726, 0xFF66BB6A, 0xFF4FC3F7, 0xFFEF5350, 0xFF000000,
    };
    private static final String[] COLOR_NAMES = {
        "Білий", "Жовтий", "Помаранчевий", "Зелений", "Блакитний", "Червоний", "Чорний",
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private final Button[] swatches = new Button[COLORS.length];

    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            if (status != null) status.setText(TempWidget.summary(MainActivity.this));
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(statusTick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(statusTick);
        super.onPause();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("BYD Temp v" + appVersion());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        status = new TextView(this);
        status.setTextSize(30);
        status.setPadding(0, 0, 0, 24);
        status.setText(TempWidget.summary(this));
        root.addView(status);

        Button toggle = button(root, overlayLabel(), 22);
        toggle.setOnClickListener(v -> {
            TempOverlay.setEnabled(this, !TempOverlay.enabled(this));
            toggle.setText(overlayLabel());
            send(TempService.ACTION_STYLE);
        });

        button(root, "Повернути віконце в центр екрану", 20)
                .setOnClickListener(v -> send(TempService.ACTION_CENTER));

        button(root, "Оновити зараз", 20)
                .setOnClickListener(v -> send(TempService.ACTION_REFRESH));

        CheckBox notify = new CheckBox(this);
        notify.setText("Системні сповіщення про зміну температури");
        notify.setTextSize(20);
        notify.setPadding(8, 24, 8, 8);
        notify.setChecked(TempService.notificationsOn(this));
        notify.setOnCheckedChangeListener((v, checked) -> {
            prefs().edit().putBoolean("notify", checked).apply();
            send(TempService.ACTION_STYLE);
        });
        root.addView(notify);

        // Background opacity
        TextView alphaLabel = label(root, "");
        SeekBar alpha = new SeekBar(this);
        alpha.setMax(100);
        alpha.setProgress(Math.round(TempOverlay.bgAlpha(this) * 100f / 255f));
        alphaLabel.setText("Прозорість фону віконця: " + (100 - alpha.getProgress()) + "%");
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                prefs().edit().putInt("bg_alpha", Math.round(p * 255f / 100f)).apply();
                alphaLabel.setText("Прозорість фону віконця: " + (100 - p) + "%");
                if (fromUser) send(TempService.ACTION_STYLE);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { send(TempService.ACTION_STYLE); }
        });
        root.addView(alpha, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Text colour
        label(root, "Колір тексту віконця:");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < COLORS.length; i++) {
            Button sw = new Button(this);
            GradientDrawable d = new GradientDrawable();
            d.setColor(COLORS[i]);
            d.setCornerRadius(16);
            d.setStroke(3, 0xFF888888);
            sw.setBackground(d);
            sw.setTextSize(22);
            sw.setTextColor(COLORS[i] == 0xFF000000 ? 0xFFFFFFFF : 0xFF000000);
            sw.setContentDescription(COLOR_NAMES[i]);
            final int color = COLORS[i];
            sw.setOnClickListener(v -> {
                prefs().edit().putInt("text_color", color).apply();
                markSwatch();
                send(TempService.ACTION_STYLE);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 110, 1f);
            lp.setMargins(8, 8, 8, 8);
            row.addView(sw, lp);
            swatches[i] = sw;
        }
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        markSwatch();

        label(root, "Віконце можна перетягнути пальцем, натискання на нього оновлює дані. "
                + "Сніжинка з'являється, коли на вулиці нижче +" + TempWidget.SNOW_BELOW + "°.");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        TempWidget.startService(this);
    }

    private void markSwatch() {
        int cur = TempOverlay.textColor(this);
        for (int i = 0; i < swatches.length; i++) swatches[i].setText(COLORS[i] == cur ? "✓" : "");
    }

    private String overlayLabel() {
        return TempOverlay.enabled(this) ? "Сховати віконце з температурою" : "Показати віконце з температурою";
    }

    private Button button(LinearLayout root, String text, int size) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(size);
        root.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private TextView label(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setPadding(0, 28, 0, 8);
        root.addView(t);
        return t;
    }

    private void send(String action) {
        Intent i = new Intent(this, TempService.class);
        i.setAction(action);
        startForegroundService(i);
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences(TempService.PREFS, Context.MODE_PRIVATE);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
