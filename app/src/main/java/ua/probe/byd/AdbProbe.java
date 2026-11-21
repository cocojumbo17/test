package ua.probe.byd;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.KeyPair;
import java.util.LinkedHashSet;
import java.util.Set;

/** v0.3: connect to the car's own ADB and run the probe as the shell user. */
public class AdbProbe {

    private final Context ctx;
    private final Probe.Out out;

    public AdbProbe(Context ctx, Probe.Out out) {
        this.ctx = ctx;
        this.out = out;
    }

    public void run() {
        out.line("=== BYD Probe 0.3 (ADB) ===");
        out.line("Android " + android.os.Build.VERSION.RELEASE + " / " + android.os.Build.DISPLAY);

        Set<Integer> ports = new LinkedHashSet<>();
        for (String p : new String[]{"service.adb.tcp.port", "persist.adb.tcp.port", "service.adb.tls.port"}) {
            String v = getprop(p);
            out.line("getprop " + p + " = " + v);
            try { int n = Integer.parseInt(v.trim()); if (n > 0) ports.add(n); } catch (Exception ignored) { }
        }
        ports.add(5555);

        KeyPair kp;
        try {
            kp = AdbClient.loadOrCreateKeys(ctx.getFilesDir());
        } catch (Exception e) {
            out.line("key error: " + e);
            return;
        }

        AdbClient adb = null;
        for (int port : ports) {
            out.line("");
            out.line("Connecting to 127.0.0.1:" + port + " ...");
            try {
                adb = AdbClient.connect("127.0.0.1", port, kp, 60000, out::line);
                break;
            } catch (Exception e) {
                out.line("  failed: " + e);
            }
        }
        if (adb == null) {
            out.line("");
            out.line("Could not connect to ADB. / Не вдалося під'єднатися до ADB.");
            out.line("=== DONE ===");
            return;
        }

        String apk = ctx.getApplicationInfo().sourceDir;
        String[][] cmds = {
            {"id", "id"},
            {"shell BYD permissions",
                "dumpsys package com.android.shell | grep -i -E 'BYDAUTO_(AC|INSTRUMENT|SENSOR|STATISTIC|SETTING)_GET'"},
            {"BYD services", "service list | grep -i byd | head -40"},
            {"probe as shell", "CLASSPATH=" + apk + " app_process /system/bin ua.probe.byd.ShellMain 2>&1"},
        };
        try {
            for (String[] c : cmds) {
                out.line("");
                out.line("### " + c[0] + "  $ " + c[1]);
                adb.shell(c[1], 180000, out::line);
            }
        } catch (Exception e) {
            out.line("shell error: " + e);
        } finally {
            adb.close();
        }
        out.line("");
        out.line("=== DONE ===");
    }

    private static String getprop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l = r.readLine();
                return l == null ? "" : l;
            }
        } catch (Exception e) {
            return "ERR " + e.getMessage();
        }
    }
}
