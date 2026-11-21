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

    private static final int[] KEY_IDS = {
        1031798832, 1320181776, 1077936184, 1077936168, 1246769216,
        796311, 1067466898, 1067466908, 489689104, 489689106,
    };
    private static final String[] KEY_NAMES = {
        "AC_TEMP_INSIDE", "AC_TEMP_INSIDE_FILTERING", "AC_TEMP_OUT", "AC_TEMP_MAIN",
        "INSTRUMENT_DD_OUT_TEMP", "SENSOR_TEMPERATURE", "SETTING_INNER_CAR_AVERAGE_TEMP",
        "SETTING_OUTSIDE_CAR_AVERAGE_TEMP", "BODYWORK_INCAR_TEMP_TRIGGER_ATOM",
        "BODYWORK_EXTERIOR_TEMP_TRIGGER_ATOM",
    };

    public TempProbe(Context ctx, Probe.Out out) {
        this.ctx = ctx;
        this.out = out;
    }

    public void run() {
        out.line("=== BYD TempProbe 0.6 ===");
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

        out.line("");
        out.line("=== KEY FEATURE IDS VIA get(int[], Class) ===");
        Object[] devs = {ac, ins, sen};
        String[] devNames = {"ac", "ins", "sen"};
        boolean classDumped = false;
        for (int i = 0; i < KEY_IDS.length; i++) {
            out.line("");
            out.line("* " + KEY_NAMES[i] + " (" + KEY_IDS[i] + ")");
            for (int d = 0; d < devs.length; d++) {
                if (devs[d] == null) continue;
                for (Class<?> type : new Class<?>[]{Integer.class, Double.class}) {
                    Object v = callRaw(devs[d], "get", new Class<?>[]{int[].class, Class.class},
                            new int[]{KEY_IDS[i]}, type);
                    out.line("  " + devNames[d] + "/" + type.getSimpleName() + ": " + describe(v));
                    if (!classDumped && v != null && !(v instanceof Throwable)) {
                        dumpClass(v.getClass());
                        classDumped = true;
                    }
                }
            }
        }

        dumpDeclared("android.hardware.bydauto.AbsBYDAutoDevice");
        dumpDeclared("android.hardware.bydauto.ac.BYDAutoAcDevice");
        for (String l : new String[]{
                "android.hardware.bydauto.ac.AbsBYDAutoAcListener",
                "android.hardware.bydauto.AbsBYDAutoListener",
                "android.hardware.bydauto.IBYDAutoListener"}) {
            dumpDeclared(l);
        }
        tryHiddenGetters(ac, "ac", 1000);
        tryHiddenGetters(ins, "ins", 1007);

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

    /** Calls a method and returns the raw result, or the Throwable on failure. */
    private Object callRaw(Object inst, String name, Class<?>[] types, Object... args) {
        if (inst == null) return new IllegalStateException("no device");
        Future<Object> f = pool.submit(() -> inst.getClass().getMethod(name, types).invoke(inst, args));
        try {
            return f.get(1500, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            f.cancel(true);
            while (t.getCause() != null) t = t.getCause();
            return t;
        }
    }

    /** Prints all fields (including inherited ones) of a result object. */
    private static String describe(Object v) {
        if (v == null) return "null";
        if (v instanceof Throwable) return "ERR " + root((Throwable) v);
        if (v instanceof Number || v instanceof String || v instanceof Boolean || v.getClass().isArray()) return fmt(v);
        StringBuilder sb = new StringBuilder(v.getClass().getSimpleName()).append(" {");
        for (Class<?> c = v.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    sb.append(' ').append(f.getName()).append('=').append(fmt(f.get(v))).append(';');
                } catch (Throwable ignored) { }
            }
        }
        return sb.append(" }").toString();
    }

    /** Lists every declared method (any visibility) with full parameter types. */
    private void dumpDeclared(String name) {
        out.line("");
        out.line("=== DECLARED METHODS: " + name + " ===");
        Class<?> c;
        try { c = Class.forName(name); } catch (Throwable t) { out.line("  not found"); return; }
        out.line("  superclass: " + (c.getSuperclass() == null ? "-" : c.getSuperclass().getName())
                + "  abstract=" + Modifier.isAbstract(c.getModifiers()) + "  interface=" + c.isInterface());
        for (Method m : c.getDeclaredMethods()) {
            StringBuilder sb = new StringBuilder("  ").append(Modifier.toString(m.getModifiers())).append(' ')
                    .append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
            Class<?>[] pt = m.getParameterTypes();
            for (int i = 0; i < pt.length; i++) { if (i > 0) sb.append(", "); sb.append(pt[i].getName()); }
            out.line(sb.append(')').toString());
        }
    }

    /**
     * Read-only: calls declared get*-style methods of the base device class that take
     * (int) or (int, int) with the key feature ids, e.g. getInt(deviceType, featureId).
     */
    private void tryHiddenGetters(Object dev, String devName, int deviceType) {
        out.line("");
        out.line("=== HIDDEN GETTERS on " + devName + " ===");
        if (dev == null) { out.line("  no device"); return; }
        for (Class<?> c = dev.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName();
                if (!(n.equals("getInt") || n.equals("getDouble") || n.equals("getFloat") || n.equals("getValue")
                        || n.equals("getIntArray") || n.equals("getDoubleArray") || n.equals("getFloatArray"))) continue;
                Class<?>[] pt = m.getParameterTypes();
                boolean one = pt.length == 1 && pt[0] == int.class;
                boolean two = pt.length == 2 && pt[0] == int.class && pt[1] == int.class;
                if (!one && !two) continue;
                m.setAccessible(true);
                for (int i = 0; i < KEY_IDS.length; i++) {
                    Object[] args = one ? new Object[]{KEY_IDS[i]} : new Object[]{deviceType, KEY_IDS[i]};
                    Future<Object> f = pool.submit(() -> m.invoke(dev, args));
                    String r;
                    try { r = describe(f.get(1500, TimeUnit.MILLISECONDS)); }
                    catch (Throwable t) { f.cancel(true); r = "ERR " + root(t); }
                    out.line("  " + c.getSimpleName() + "." + n + (one ? "(" : "(" + deviceType + ", ")
                            + KEY_NAMES[i] + ") = " + r);
                }
            }
        }
    }

    private void dumpClass(Class<?> c) {
        out.line("  [class " + c.getName() + "]");
        for (Method m : c.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            out.line("    method " + m.getName() + "(" + m.getParameterTypes().length + " args) -> "
                    + m.getReturnType().getSimpleName());
        }
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
