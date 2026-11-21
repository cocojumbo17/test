package ua.probe.byd;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.PermissionGroupInfo;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;

/**
 * Read-only probe of BYD's internal "bydauto" Android API.
 * It only calls methods named get* / is* and never calls set* methods.
 */
public class Probe {

    public interface Out { void line(String s); }

    private static final String[] KNOWN = {
        "android.hardware.bydauto.ac.BYDAutoAcDevice",
        "android.hardware.bydauto.sensor.BYDAutoSensorDevice",
        "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
        "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
        "android.hardware.bydauto.setting.BYDAutoSettingDevice",
        "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
        "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
        "android.hardware.bydauto.engine.BYDAutoEngineDevice",
        "android.hardware.bydauto.tyre.BYDAutoTyreDevice",
        "android.hardware.bydauto.charging.BYDAutoChargingDevice",
        "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
        "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
    };

    private static final String[] FRAMEWORK_DIRS = {
        "/system/framework", "/system_ext/framework", "/system/system_ext/framework",
        "/product/framework", "/vendor/framework",
    };

    private final Context ctx;
    private final Out out;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final List<String> tempHits = new ArrayList<>();

    public Probe(Context ctx, Out out) {
        this.ctx = ctx;
        this.out = out;
    }

    public void run() {
        out.line("=== BYD Probe 0.1 ===");
        out.line("Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        out.line("Model: " + android.os.Build.MODEL + " / " + android.os.Build.DISPLAY);
        out.line("");

        listBydPermissions();

        Map<String, ClassLoader> classes = new LinkedHashMap<>();
        for (String k : KNOWN) classes.put(k, null);
        discoverInFrameworkJars(classes);

        out.line("");
        out.line("=== CLASSES (" + classes.size() + ") ===");
        for (Map.Entry<String, ClassLoader> e : classes.entrySet()) {
            probeClass(e.getKey(), e.getValue());
        }

        out.line("");
        out.line("=== TEMPERATURE SUMMARY ===");
        if (tempHits.isEmpty()) out.line("(no temperature values could be read)");
        for (String h : tempHits) out.line(h);
        out.line("");
        out.line("=== DONE ===");
        pool.shutdownNow();
    }

    private void listBydPermissions() {
        out.line("=== BYD PERMISSIONS ON THIS CAR ===");
        PackageManager pm = ctx.getPackageManager();
        int found = 0;
        try {
            List<PermissionGroupInfo> groups = new ArrayList<>(pm.getAllPermissionGroups(0));
            groups.add(null);
            for (PermissionGroupInfo g : groups) {
                List<PermissionInfo> perms;
                try {
                    perms = pm.queryPermissionsByGroup(g == null ? null : g.name, 0);
                } catch (Exception ex) { continue; }
                for (PermissionInfo p : perms) {
                    if (p.name != null && p.name.toUpperCase(Locale.ROOT).contains("BYD")) {
                        boolean granted = ctx.checkSelfPermission(p.name) == PackageManager.PERMISSION_GRANTED;
                        out.line(p.name + "  level=0x" + Integer.toHexString(p.protectionLevel)
                                + (granted ? "  [GRANTED]" : ""));
                        found++;
                    }
                }
            }
        } catch (Throwable t) {
            out.line("permission scan failed: " + t);
        }
        if (found == 0) out.line("(none found)");
        out.line("");
    }

    @SuppressWarnings("deprecation")
    private void discoverInFrameworkJars(Map<String, ClassLoader> classes) {
        out.line("=== SCANNING FRAMEWORK JARS ===");
        for (String dir : FRAMEWORK_DIRS) {
            File[] files = new File(dir).listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName();
                if (!(n.endsWith(".jar") || n.endsWith(".apk"))) continue;
                try {
                    DexFile dex = new DexFile(f.getAbsolutePath());
                    ClassLoader loader = null;
                    int count = 0;
                    Enumeration<String> en = dex.entries();
                    while (en.hasMoreElements()) {
                        String c = en.nextElement();
                        if (c.contains("bydauto") && c.endsWith("Device") && !c.contains("$")) {
                            if (loader == null) {
                                loader = new PathClassLoader(f.getAbsolutePath(), ctx.getClassLoader());
                            }
                            if (!classes.containsKey(c) || classes.get(c) == null) classes.put(c, loader);
                            count++;
                        }
                    }
                    if (count > 0) out.line(f.getAbsolutePath() + ": " + count + " device classes");
                    dex.close();
                } catch (Throwable ignored) {
                    // jar without dex code, or no read access
                }
            }
        }
    }

    private void probeClass(String name, ClassLoader extra) {
        out.line("");
        out.line("--- " + name);
        try {
            probeClassInner(name, extra);
        } catch (Throwable t) {
            out.line("  probe error: " + root(t));
        }
    }

    private void probeClassInner(String name, ClassLoader extra) {
        Class<?> cls = null;
        try {
            cls = Class.forName(name);
        } catch (Throwable t) {
            if (extra != null) {
                try { cls = Class.forName(name, true, extra); } catch (Throwable t2) { /* below */ }
            }
        }
        if (cls == null) {
            out.line("  not available");
            return;
        }

        for (Field f : cls.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isFinal(m)) {
                String fn = f.getName().toUpperCase(Locale.ROOT);
                if (fn.contains("TEMP")) {
                    try {
                        f.setAccessible(true);
                        out.line("  const " + f.getName() + " = " + fmt(f.get(null)));
                    } catch (Throwable ignored) { }
                }
            }
        }

        Object inst = getInstance(cls);
        if (inst == null) out.line("  getInstance: FAILED (only method list below)");

        Method[] methods = cls.getMethods();
        Arrays.sort(methods, (a, b) -> a.getName().compareTo(b.getName()));
        for (Method m : methods) {
            if (m.getDeclaringClass() == Object.class) continue;
            String mn = m.getName();
            boolean getter = mn.startsWith("get") || mn.startsWith("is");
            boolean isTemp = mn.toLowerCase(Locale.ROOT).contains("temp");
            Class<?>[] pt = m.getParameterTypes();
            String sig = mn + "(" + typeList(pt) + ")";

            if (inst == null || !getter || Modifier.isStatic(m.getModifiers()) || mn.equals("getInstance")) {
                if (isTemp) out.line("  " + sig);
                continue;
            }
            if (pt.length == 0) {
                String r = call(inst, m);
                out.line("  " + sig + " -> " + r);
                if (isTemp) tempHits.add(shortName(name) + "." + sig + " -> " + r);
            } else if (isTemp && pt.length == 1 && pt[0] == int.class) {
                for (int a = 0; a <= 3; a++) {
                    String r = call(inst, m, a);
                    String s = mn + "(" + a + ")";
                    out.line("  " + s + " -> " + r);
                    tempHits.add(shortName(name) + "." + s + " -> " + r);
                }
            } else {
                out.line("  " + sig + "  (skipped: needs args)");
            }
        }
    }

    private Object getInstance(Class<?> cls) {
        Context pc = new PermissiveContext(ctx);
        try {
            Method gi = cls.getMethod("getInstance", Context.class);
            return gi.invoke(null, pc);
        } catch (Throwable t) {
            try {
                Method gi = cls.getMethod("getInstance");
                return gi.invoke(null);
            } catch (Throwable t2) {
                out.line("  getInstance error: " + root(t));
                return null;
            }
        }
    }

    private String call(Object inst, Method m, Object... args) {
        Future<Object> f = pool.submit(() -> m.invoke(inst, args));
        try {
            return fmt(f.get(1500, TimeUnit.MILLISECONDS));
        } catch (TimeoutException te) {
            f.cancel(true);
            return "TIMEOUT";
        } catch (Throwable t) {
            return "ERR " + root(t);
        }
    }

    private static String fmt(Object v) {
        if (v == null) return "null";
        if (v.getClass().isArray()) {
            int n = Array.getLength(v);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n && i < 32; i++) {
                if (i > 0) sb.append(", ");
                sb.append(fmt(Array.get(v, i)));
            }
            if (n > 32) sb.append(", ...");
            return sb.append("]").toString();
        }
        return String.valueOf(v);
    }

    private static String root(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private static String typeList(Class<?>[] pt) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pt.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(pt[i].getSimpleName());
        }
        return sb.toString();
    }

    private static String shortName(String full) {
        int i = full.lastIndexOf('.');
        return i < 0 ? full : full.substring(i + 1);
    }
}
