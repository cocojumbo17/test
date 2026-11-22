package ua.probe.byd;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

import java.security.KeyPair;

/**
 * Temporary, read-only check: which displays exist (main screen, cluster, HUD) and
 * which BYD services / API parts relate to the HUD. Nothing is written or launched.
 */
public class HudProbe {

    public interface Out { void line(String s); }

    private final Context ctx;
    private final Out out;

    public HudProbe(Context ctx, Out out) {
        this.ctx = ctx;
        this.out = out;
    }

    @SuppressWarnings("deprecation")
    public void run() {
        out.line("=== Displays visible to the app ===");
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            for (Display d : dm.getDisplays()) describe(d);
            out.line("-- presentation category:");
            for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) describe(d);
        } catch (Throwable t) {
            out.line("error: " + t);
        }

        String apk = ctx.getApplicationInfo().sourceDir;
        String[][] cmds = {
            {"physical/logical displays", "dumpsys display | grep -E 'DisplayDeviceInfo\\{|mDisplayId=|mBaseDisplayInfo=' | head -40"},
            {"SurfaceFlinger displays", "dumpsys SurfaceFlinger --display-id 2>&1 | head -20"},
            {"display-related services", "service list | grep -iE 'hud|cluster|instrument|meter|display' | head -40"},
            {"display-related packages", "pm list packages | grep -iE 'hud|cluster|instrument|meter|dashboard' | head -40"},
            {"BYD API (classes, methods, HUD constants)",
                "CLASSPATH=" + apk + " app_process /system/bin ua.probe.byd.ShellMain hud 2>&1"},
        };

        AdbClient adb = null;
        try {
            KeyPair kp = AdbClient.loadOrCreateKeys(ctx.getFilesDir());
            adb = AdbClient.connect("127.0.0.1", 5555, kp, 60_000, out::line);
            for (String[] c : cmds) {
                out.line("");
                out.line("### " + c[0]);
                adb.shell(c[1], 120_000, out::line);
            }
        } catch (Exception e) {
            out.line("ADB error: " + e);
        } finally {
            if (adb != null) adb.close();
        }
        out.line("");
        out.line("=== DONE ===");
    }

    private void describe(Display d) {
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int f = d.getFlags();
        out.line("id=" + d.getDisplayId() + " name=\"" + d.getName() + "\" " + m.widthPixels + "x" + m.heightPixels
                + " state=" + d.getState()
                + ((f & Display.FLAG_PRIVATE) != 0 ? " PRIVATE" : "")
                + ((f & Display.FLAG_PRESENTATION) != 0 ? " PRESENTATION" : "")
                + ((f & Display.FLAG_SECURE) != 0 ? " SECURE" : ""));
    }
}
