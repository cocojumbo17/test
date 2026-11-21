package ua.probe.byd;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        Button start = new Button(this);
        start.setText("Температури через ADB (v0.7)");
        start.setTextSize(22);
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

        start.setOnClickListener(v -> runJob(start, start2, true));
        start2.setOnClickListener(v -> runJob(start, start2, false));
    }

    private void runJob(Button a, Button b, boolean adb) {
        a.setEnabled(false);
        b.setEnabled(false);
        all.setLength(0);
        log.setText("");
        new Thread(() -> {
            if (adb) new AdbProbe(getApplicationContext(), this::append).run();
            else new Probe2(getApplicationContext(), this::append).run();
            save();
            runOnUiThread(() -> { a.setEnabled(true); b.setEnabled(true); });
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
    private void save() {
        byte[] data = all.toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder where = new StringBuilder();

        File[] targets = {
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "byd_probe.txt"),
            new File(getExternalFilesDir(null), "byd_probe.txt"),
        };
        for (File f : targets) {
            if (write(f, data)) where.append(f.getAbsolutePath()).append('\n');
        }

        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self")) continue;
                File f = new File(v, "byd_probe.txt");
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
