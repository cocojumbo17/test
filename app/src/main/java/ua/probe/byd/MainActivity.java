package ua.probe.byd;

import android.app.Activity;
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

    private static final long STATUS_REFRESH_MS = 2_000;
    private static final int[] TEXT_COLORS = {
        0xFFFFFFFF, 0xFFFFEB3B, 0xFFFFA726, 0xFF66BB6A, 0xFF4FC3F7, 0xFFEF5350, 0xFF000000,
    };
    private static final String[] TEXT_COLOR_NAMES = {
        "Білий", "Жовтий", "Помаранчевий", "Зелений", "Блакитний", "Червоний", "Чорний",
    };
    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Button[] colorButtons = new Button[TEXT_COLORS.length];
    private AppPrefs prefs;
    private TextView status;

    private final Runnable statusLoop = new Runnable() {
        @Override public void run() {
            status.setText(prefs.currentSummary());
            ui.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        prefs = AppPrefs.of(this);
        setTitle("BYD Temp v" + appVersion());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        addStatus(root);
        addOverlayButtons(root);
        addNotificationOption(root);
        addOpacitySlider(root);
        addTextSizeSlider(root);
        addTextColorPicker(root);
        addLabel(root, "Віконце можна перетягнути пальцем, натискання на нього оновлює дані. "
                + "Сніжинка з'являється, коли на вулиці нижче +" + TemperatureText.SNOW_BELOW_C + "°.");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        TempService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(statusLoop);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(statusLoop);
        super.onPause();
    }

    // ---- sections ----

    private void addStatus(LinearLayout root) {
        status = new TextView(this);
        status.setTextSize(30);
        status.setPadding(0, 0, 0, 24);
        status.setText(prefs.currentSummary());
        root.addView(status);
    }

    private void addOverlayButtons(LinearLayout root) {
        Button toggle = addButton(root, overlayToggleText(), 22);
        toggle.setOnClickListener(v -> {
            prefs.setOverlayEnabled(!prefs.overlayEnabled());
            toggle.setText(overlayToggleText());
            TempService.send(this, TempService.ACTION_STYLE);
        });
        addButton(root, "Повернути віконце в центр екрана", 20)
                .setOnClickListener(v -> TempService.send(this, TempService.ACTION_CENTER));
        addButton(root, "Оновити зараз", 20)
                .setOnClickListener(v -> TempService.send(this, TempService.ACTION_REFRESH));
    }

    private void addNotificationOption(LinearLayout root) {
        CheckBox notify = new CheckBox(this);
        notify.setText("Системні сповіщення про зміну температури");
        notify.setTextSize(20);
        notify.setPadding(8, 24, 8, 8);
        notify.setChecked(prefs.notificationsEnabled());
        notify.setOnCheckedChangeListener((v, checked) -> {
            prefs.setNotificationsEnabled(checked);
            TempService.send(this, TempService.ACTION_STYLE);
        });
        root.addView(notify);
    }

    /** The slider shows opacity in percent; the label shows its inverse, transparency. */
    private void addOpacitySlider(LinearLayout root) {
        TextView label = addLabel(root, "");
        int opacityPercent = Math.round(prefs.backgroundAlpha() * 100f / 255f);
        label.setText(transparencyText(opacityPercent));
        addSlider(root, 100, opacityPercent, percent -> {
            prefs.setBackgroundAlpha(Math.round(percent * 255f / 100f));
            label.setText(transparencyText(percent));
        });
    }

    private void addTextSizeSlider(LinearLayout root) {
        TextView label = addLabel(root, textSizeText(prefs.textSize()));
        addSlider(root, AppPrefs.MAX_TEXT_SIZE - AppPrefs.MIN_TEXT_SIZE,
                prefs.textSize() - AppPrefs.MIN_TEXT_SIZE, step -> {
            int sp = AppPrefs.MIN_TEXT_SIZE + step;
            prefs.setTextSize(sp);
            label.setText(textSizeText(sp));
        });
    }

    private void addTextColorPicker(LinearLayout root) {
        addLabel(root, "Колір тексту віконця:");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TEXT_COLORS.length; i++) {
            colorButtons[i] = colorButton(TEXT_COLORS[i], TEXT_COLOR_NAMES[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 110, 1f);
            lp.setMargins(8, 8, 8, 8);
            row.addView(colorButtons[i], lp);
        }
        root.addView(row, fullWidth());
        markSelectedColor();
    }

    // ---- building blocks ----

    private interface IntChange { void onChange(int value); }

    /** Saves on every move; the label is restyled while dragging and once more on release. */
    private void addSlider(LinearLayout root, int max, int progress, IntChange onChange) {
        SeekBar slider = new SeekBar(this);
        slider.setMax(max);
        slider.setProgress(progress);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                onChange.onChange(value);
                if (fromUser) TempService.send(MainActivity.this, TempService.ACTION_STYLE);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                TempService.send(MainActivity.this, TempService.ACTION_STYLE);
            }
        });
        root.addView(slider, fullWidth());
    }

    private Button colorButton(int color, String name) {
        Button button = new Button(this);
        GradientDrawable swatch = new GradientDrawable();
        swatch.setColor(color);
        swatch.setCornerRadius(16);
        swatch.setStroke(3, 0xFF888888);
        button.setBackground(swatch);
        button.setTextSize(22);
        button.setTextColor(color == BLACK ? WHITE : BLACK);
        button.setContentDescription(name);
        button.setOnClickListener(v -> {
            prefs.setTextColor(color);
            markSelectedColor();
            TempService.send(this, TempService.ACTION_STYLE);
        });
        return button;
    }

    private void markSelectedColor() {
        int selected = prefs.textColor();
        for (int i = 0; i < colorButtons.length; i++) {
            colorButtons[i].setText(TEXT_COLORS[i] == selected ? "✓" : "");
        }
    }

    private Button addButton(LinearLayout root, String text, int textSize) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(textSize);
        root.addView(button, fullWidth());
        return button;
    }

    private TextView addLabel(LinearLayout root, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(18);
        label.setPadding(0, 28, 0, 8);
        root.addView(label);
        return label;
    }

    private static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ---- texts ----

    private String overlayToggleText() {
        return prefs.overlayEnabled() ? "Сховати віконце з температурою" : "Показати віконце з температурою";
    }

    private static String transparencyText(int opacityPercent) {
        return "Прозорість фону віконця: " + (100 - opacityPercent) + "%";
    }

    private static String textSizeText(int sp) {
        return "Розмір шрифту віконця: " + sp;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
