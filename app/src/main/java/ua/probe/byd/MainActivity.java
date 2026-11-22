package ua.probe.byd;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private TextView log;
    private ScrollView scroll;
    private final StringBuilder all = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
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
        root.setPadding(24, 24, 24, 24);

        status = new TextView(this);
        status.setTextSize(30);
        status.setPadding(0, 0, 0, 16);
        status.setText(TempWidget.summary(this));
        root.addView(status);

        Button overlayBtn = new Button(this);
        overlayBtn.setTextSize(22);
        overlayBtn.setText(TempOverlay.enabled(this) ? "Сховати віконце з температурою" : "Показати віконце з температурою");
        root.addView(overlayBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        overlayBtn.setOnClickListener(v -> {
            boolean on = !TempOverlay.enabled(this);
            TempOverlay.setEnabled(this, on);
            overlayBtn.setText(on ? "Сховати віконце з температурою" : "Показати віконце з температурою");
            TempWidget.startService(this);
        });

        Button service = new Button(this);
        service.setText("Оновити зараз");
        service.setTextSize(20);
        root.addView(service, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        service.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(this, TempService.class);
            i.setAction(TempService.ACTION_REFRESH);
            startForegroundService(i);
        });
        TempWidget.startService(this);

        Button start = new Button(this);
        start.setText("Діагностика температур (ADB)");
        start.setTextSize(18);
        root.addView(start, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button start2 = new Button(this);
        start2.setText("Probe v0.2 (без ADB)");
        start2.setTextSize(18);
        root.addView(start2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(14);
        log.setTextIsSelectable(true);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        Button start3 = new Button(this);
        start3.setText("CarPropertyManager (без ADB)");
        start3.setTextSize(18);
        root.addView(start3, 5, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button[] all3 = {start, start2, start3};
        start.setOnClickListener(v -> runJob(all3, 0));
        start2.setOnClickListener(v -> runJob(all3, 1));
        start3.setOnClickListener(v -> runJob(all3, 2));
    }

    private void runJob(Button[] buttons, int mode) {
        for (Button b : buttons) b.setEnabled(false);
        all.setLength(0);
        log.setText("");
        new Thread(() -> {
            if (mode == 0) new AdbProbe(getApplicationContext(), this::append).run();
            else if (mode == 1) new Probe2(getApplicationContext(), this::append).run();
            else new CarProbe(getApplicationContext(), this::append).run();
            save();
            runOnUiThread(() -> { for (Button b : buttons) b.setEnabled(true); });
        }).start();
    }

    private void append(String s) {
        all.append(s).append('\n');
        runOnUiThread(() -> {
            log.append(s + "\n");
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    /** Saves the report to Downloads, the app folder and the root of any USB stick. */
    /** App version, e.g. "1.2". */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void save() {
        String ver = appVersion();
        String name = "byd_probe_v" + ver + ".txt";
        String header = "BYD Temp v" + ver + "  (" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.ROOT).format(new java.util.Date()) + ")\n";
        byte[] data = (header + all).getBytes(StandardCharsets.UTF_8);
        StringBuilder where = new StringBuilder();

        File[] targets = {
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name),
            new File(getExternalFilesDir(null), name),
        };
        for (File f : targets) {
            if (write(f, data)) where.append(f.getAbsolutePath()).append('\n');
        }

        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self")) continue;
                File f = new File(v, name);
                if (write(f, data)) where.append(f.getAbsolutePath()).append('\n');
            }
        }

        String msg = where.length() > 0 ? "\nSaved to:\n" + where : "\nCould not save file — take photos of the screen.";
        runOnUiThread(() -> {
            log.append(msg);
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private static boolean write(File f, byte[] data) {
        try {
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
