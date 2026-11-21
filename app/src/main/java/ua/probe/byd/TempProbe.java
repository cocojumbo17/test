package ua.probe.byd;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dalvik.system.DexFile;

/**
 * v0.4, read-only, meant to run as shell via ShellMain "temps":
 *  1. samples the known temperature getters several times,
 *  2. lists temperature-related constants in all bydauto classes (incl. inner ones),
 *  3. tries the generic get(int[], Class) with those constants.
 */
public class TempProbe {

    private final Context ctx;
    private final Probe.Out out;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public TempProbe(Context ctx, Probe.Out out) {
        this.ctx = ctx;
        this.out = out;
    }

    public void run() {
        out.line("=== BYD TempProbe 0.4 ===");
        Object ac = device("android.hardware.bydauto.ac.BYDAutoAcDevice");
        Object ins = device("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
        Object sen = device("android.hardware.bydauto.sensor.BYDAutoSensorDevice");

        out.line("");
        out.line("=== SAMPLES (3 x every 3 s) ===");
        for (int round = 1; round <= 3; round++) {
            out.line("-- round " + round);
            for (int area = 0; area <= 8; area++) {
                out.line("ac.getTemprature(" + area + ") = " + call(ac, "getTemprature", new Class<?>[]{int.class}, area));
            }
            out.line("ins.getOutCarTemperature() = " + call(ins, "getOutCarTemperature", new Class<?>[0]));
            out.line("sen.getTemperatureSensorValue() = " + call(sen, "getTemperatureSensorValue", new Class<?>[0]));
            out.line("sen.getHumiditySensorValue() = " + call(sen, "getHumiditySensorValue", new Class<?>[0]));
            out.line("ac.getAcStartState() = " + call(ac, "getAcStartState", new Class<?>[0]));
            if (round < 3) sleep(3000);
        }

        List<int[]> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        scanConstants(ids, names);

        out.line("");
        out.line("=== GENERIC get(int[], Class) WITH TEMP CONSTANTS ===");
        Object[] devs = {ac, ins, sen};
        String[] devNames = {"ac", "ins", "sen"};
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i)[0];
            for (int d = 0; d < devs.length; d++) {
                if (devs[d] == null) continue;
                for (Class<?> type : new Class<?>[]{Integer.class, Double.class}) {
                    String r = call(devs[d], "get", new Class<?>[]{int[].class, Class.class}, new int[]{id}, type);
                    if (!r.startsWith("ERR")) {
                        out.line(devNames[d] + ".get([" + id + "], " + type.getSimpleName() + ") = " + r
                                + "   <- " + names.get(i));
                    }
                }
            }
        }

        out.line("");
        out.line("=== DONE ===");
        pool.shutdownNow();
    }

    private Object device(String cls) {
        try {
            Class<?> c = Class.forName(cls);
            return c.getMethod("getInstance", Context.class).invoke(null, new PermissiveContext(ctx));
        } catch (Throwable t) {
            out.line(cls + ": " + root(t));
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private void scanConstants(List<int[]> ids, List<String> names) {
        out.line("");
        out.line("=== TEMP CONSTANTS IN ALL bydauto CLASSES ===");
        try {
            DexFile dex = new DexFile("/system/framework/framework.jar");
            Enumeration<String> en = dex.entries();
            while (en.hasMoreElements()) {
                String cn = en.nextElement();
                if (!cn.startsWith("android.hardware.bydauto.")) continue;
                Class<?> c;
                try { c = Class.forName(cn); } catch (Throwable t) { continue; }
                for (Field f : c.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (!Modifier.isStatic(m) || !Modifier.isFinal(m) || f.getType() != int.class) continue;
                    String fn = f.getName().toUpperCase(Locale.ROOT);
                    if (!(fn.contains("TEMP") || fn.contains("HUMID") || fn.contains("INNER")
                            || fn.contains("INSIDE") || fn.contains("CABIN") || fn.contains("OUT_CAR"))) continue;
                    try {
                        f.setAccessible(true);
                        int v = f.getInt(null);
                        String label = shortName(cn) + "." + f.getName();
                        out.line(label + " = " + v);
                        // Feature ids in BYD classes are large numbers; small ones are ranges/enums.
                        if (Math.abs(v) > 1000 && v != 65535 && v > -2147480000) {
                            ids.add(new int[]{v});
                            names.add(label);
                        }
                    } catch (Throwable ignored) { }
                }
            }
            dex.close();
        } catch (Throwable t) {
            out.line("scan failed: " + root(t));
        }
        out.line("(" + ids.size() + " candidate ids for get())");
    }

    private String call(Object inst, String name, Class<?>[] types, Object... args) {
        if (inst == null) return "ERR no device";
        Future<Object> f = pool.submit(() -> {
            Method m = inst.getClass().getMethod(name, types);
            return m.invoke(inst, args);
        });
        try {
            return fmt(f.get(1500, TimeUnit.MILLISECONDS));
        } catch (Throwable t) {
            f.cancel(true);
            return "ERR " + root(t);
        }
    }

    private static String fmt(Object v) {
        if (v == null) return "null";
        if (v.getClass().isArray()) {
            StringBuilder sb = new StringBuilder("[");
            int n = Array.getLength(v);
            for (int i = 0; i < n && i < 32; i++) {
                if (i > 0) sb.append(", ");
                sb.append(fmt(Array.get(v, i)));
            }
            return sb.append("]").toString();
        }
        return String.valueOf(v);
    }

    private static String root(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private static String shortName(String full) {
        int i = full.lastIndexOf('.');
        return i < 0 ? full : full.substring(i + 1);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
