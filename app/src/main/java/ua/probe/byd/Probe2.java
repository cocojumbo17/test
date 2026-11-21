package ua.probe.byd;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Second, read-only probe: looks for temperature data outside the protected
 * bydauto API (system settings, BYD content providers, other installed apps).
 */
public class Probe2 {

    private static final String[] KEYWORDS = {
        "temp", "weather", "climate", "outside", "outdoor", "inner", "inside",
        "cabin", "aircon", "_ac", "ac_", "hvac", "adb", "debug"
    };

    private final Context ctx;
    private final Probe.Out out;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public Probe2(Context ctx, Probe.Out out) {
        this.ctx = ctx;
        this.out = out;
    }

    public void run() {
        out.line("=== BYD Probe 0.2 ===");
        out.line("Android " + android.os.Build.VERSION.RELEASE + " / " + android.os.Build.DISPLAY);
        safe("settings", this::dumpSettings);
        safe("providers", this::dumpProviders);
        safe("third-party apps", this::dumpThirdPartyApps);
        safe("ac retry", this::retryAc);
        out.line("");
        out.line("=== DONE ===");
        pool.shutdownNow();
    }

    private void safe(String what, Runnable r) {
        try { r.run(); } catch (Throwable t) { out.line(what + " failed: " + root(t)); }
    }

    // ---------- 1. System settings ----------

    private void dumpSettings() {
        dumpTable("SETTINGS.GLOBAL", Settings.Global.CONTENT_URI);
        dumpTable("SETTINGS.SYSTEM", Settings.System.CONTENT_URI);
        dumpTable("SETTINGS.SECURE", Settings.Secure.CONTENT_URI);
    }

    private void dumpTable(String title, Uri uri) {
        out.line("");
        out.line("=== " + title + " (filtered) ===");
        Cursor c = null;
        int total = 0, shown = 0;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{"name", "value"}, null, null, null);
            if (c == null) { out.line("(no access)"); return; }
            while (c.moveToNext()) {
                total++;
                String name = c.getString(0);
                String value = c.getString(1);
                if (name == null) continue;
                String n = name.toLowerCase(Locale.ROOT);
                boolean hit = n.contains("byd");
                for (String k : KEYWORDS) if (n.contains(k)) { hit = true; break; }
                if (hit && shown < 300) {
                    out.line(name + " = " + value);
                    shown++;
                }
            }
            out.line("(" + shown + " shown of " + total + ")");
        } catch (Throwable t) {
            out.line("error: " + root(t));
        } finally {
            if (c != null) c.close();
        }
    }

    // ---------- 2. Content providers ----------

    private void dumpProviders() {
        out.line("");
        out.line("=== BYD CONTENT PROVIDERS ===");
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_PROVIDERS);
        for (PackageInfo p : pkgs) {
            if (p.providers == null) continue;
            for (ProviderInfo pi : p.providers) {
                String auth = pi.authority == null ? "" : pi.authority;
                boolean byd = p.packageName.toLowerCase(Locale.ROOT).contains("byd")
                        || auth.toLowerCase(Locale.ROOT).contains("byd");
                if (!byd) continue;

                String rp = pi.readPermission;
                boolean canRead = pi.exported && (rp == null
                        || ctx.checkSelfPermission(rp) == PackageManager.PERMISSION_GRANTED);
                out.line("");
                out.line("* " + auth + "  [" + p.packageName + "]");
                out.line("  exported=" + pi.exported + " read=" + rp + " -> "
                        + (canRead ? "ACCESSIBLE" : "no access"));
                if (canRead) {
                    for (String a : auth.split(";")) tryQuery("content://" + a);
                }
            }
        }
    }

    private void tryQuery(String uriStr) {
        Future<String> f = pool.submit(() -> queryToString(uriStr));
        try {
            out.line(f.get(3, TimeUnit.SECONDS));
        } catch (Throwable t) {
            f.cancel(true);
            out.line("  query " + uriStr + " -> " + root(t));
        }
    }

    private String queryToString(String uriStr) {
        ContentResolver cr = ctx.getContentResolver();
        StringBuilder sb = new StringBuilder("  query " + uriStr);
        Cursor c = null;
        try {
            c = cr.query(Uri.parse(uriStr), null, null, null, null);
            if (c == null) return sb.append(" -> null").toString();
            sb.append(" -> ").append(c.getCount()).append(" rows\n  columns: ");
            String[] cols = c.getColumnNames();
            sb.append(String.join(", ", cols));
            int rows = 0;
            while (c.moveToNext() && rows < 40) {
                sb.append("\n   ");
                for (int i = 0; i < cols.length; i++) {
                    String v;
                    try { v = c.getString(i); } catch (Throwable t) { v = "<blob>"; }
                    sb.append(cols[i]).append('=').append(v).append("; ");
                }
                rows++;
            }
        } catch (Throwable t) {
            sb.append(" -> ").append(root(t));
        } finally {
            if (c != null) c.close();
        }
        return sb.toString();
    }

    // ---------- 3. Other third-party apps with BYD permissions ----------

    private void dumpThirdPartyApps() {
        out.line("");
        out.line("=== THIRD-PARTY APPS AND THEIR BYD PERMISSIONS ===");
        PackageManager pm = ctx.getPackageManager();
        for (PackageInfo p : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            ApplicationInfo ai = p.applicationInfo;
            if (ai == null || (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            CharSequence label = pm.getApplicationLabel(ai);
            out.line("");
            out.line("* " + p.packageName + " (" + label + ") v" + p.versionName);
            if (p.requestedPermissions == null) continue;
            for (int i = 0; i < p.requestedPermissions.length; i++) {
                String perm = p.requestedPermissions[i];
                String up = perm.toUpperCase(Locale.ROOT);
                if (!(up.contains("BYD") || up.contains("ACCESSIBILITY") || up.contains("SECURE_SETTINGS")
                        || up.contains("DUMP") || up.contains("READ_LOGS"))) continue;
                boolean granted = p.requestedPermissionsFlags != null
                        && (p.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                out.line("   " + perm + (granted ? "  [GRANTED]" : ""));
            }
        }
    }

    // ---------- 4. Retry climate with other area codes ----------

    private void retryAc() {
        out.line("");
        out.line("=== AC RETRY ===");
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice");
            Object inst = cls.getMethod("getInstance", Context.class).invoke(null, new PermissiveContext(ctx));
            Method m = cls.getMethod("getTemprature", int.class);
            for (int a = 4; a <= 6; a++) out.line("getTemprature(" + a + ") -> " + call(inst, m, a));
        } catch (Throwable t) {
            out.line("error: " + root(t));
        }
    }

    private String call(Object inst, Method m, Object... args) {
        Future<Object> f = pool.submit(() -> m.invoke(inst, args));
        try {
            return String.valueOf(f.get(1500, TimeUnit.MILLISECONDS));
        } catch (Throwable t) {
            f.cancel(true);
            return "ERR " + root(t);
        }
    }

    private static String root(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
