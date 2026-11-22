package ua.probe.byd;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dalvik.system.PathClassLoader;

/**
 * Read-only probe of the standard Android car API (android.car.CarPropertyManager),
 * run as a normal app, without ADB. Everything goes through reflection, because the
 * car library is not part of the regular SDK.
 */
public class CarProbe {

    // Standard VehiclePropertyIds (AOSP)
    static final int ENV_OUTSIDE_TEMPERATURE = 0x11600703;
    static final int HVAC_TEMPERATURE_CURRENT = 0x15600502;
    static final int HVAC_TEMPERATURE_SET = 0x15600503;

    private static final String[] CAR_PERMS = {
        "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
        "android.car.permission.CAR_INFO",
        "android.car.permission.CAR_ENERGY",
        "android.car.permission.CAR_POWERTRAIN",
        "android.car.permission.CAR_SPEED",
        "android.car.permission.READ_CAR_DISPLAY_UNITS",
        "android.car.permission.CONTROL_CAR_CLIMATE",
    };

    private final Context ctx;
    private final Probe.Out out;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ClassLoader loader;

    public CarProbe(Context ctx, Probe.Out out) {
        this.ctx = ctx;
        this.out = out;
    }

    public void run() {
        out.line("=== CarPropertyManager probe (no ADB) ===");
        PackageManager pm = ctx.getPackageManager();
        out.line("feature android.hardware.type.automotive = "
                + pm.hasSystemFeature("android.hardware.type.automotive"));
        out.line("file /system/framework/android.car.jar exists = "
                + new File("/system/framework/android.car.jar").exists());
        for (String p : CAR_PERMS) {
            boolean g = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
            out.line("permission " + p + (g ? " [GRANTED]" : " [not granted]"));
        }

        Class<?> carCls = loadClass("android.car.Car");
        if (carCls == null) {
            out.line("android.car.Car not available -> no standard car API on this firmware");
            done();
            return;
        }

        Object car, cpm;
        try {
            car = carCls.getMethod("createCar", Context.class).invoke(null, ctx);
            out.line("Car.createCar -> " + (car == null ? "null" : "ok"));
            if (car == null) { done(); return; }
            try {
                Object connected = carCls.getMethod("isConnected").invoke(car);
                out.line("isConnected = " + connected);
            } catch (Throwable ignored) { }
            cpm = carCls.getMethod("getCarManager", String.class).invoke(car, "property");
            out.line("getCarManager(\"property\") -> " + (cpm == null ? "null" : cpm.getClass().getName()));
            if (cpm == null) { done(); return; }
        } catch (Throwable t) {
            out.line("connect failed: " + root(t));
            done();
            return;
        }

        out.line("");
        out.line("=== STANDARD TEMPERATURE PROPERTIES ===");
        readOne(cpm, "ENV_OUTSIDE_TEMPERATURE", ENV_OUTSIDE_TEMPERATURE, 0);
        for (int area : new int[]{0, 1, 4, 0x31, 0x44, 117}) {
            readOne(cpm, "HVAC_TEMPERATURE_CURRENT area " + area, HVAC_TEMPERATURE_CURRENT, area);
        }
        readOne(cpm, "HVAC_TEMPERATURE_SET area 1", HVAC_TEMPERATURE_SET, 1);

        out.line("");
        out.line("=== ALL PROPERTIES VISIBLE TO THIS APP ===");
        try {
            List<?> configs = (List<?>) cpm.getClass().getMethod("getPropertyList").invoke(cpm);
            out.line("count = " + (configs == null ? 0 : configs.size()));
            if (configs != null) {
                int shown = 0;
                for (Object cfg : configs) {
                    if (shown++ >= 400) { out.line("... (truncated)"); break; }
                    describeConfig(cpm, cfg);
                }
            }
        } catch (Throwable t) {
            out.line("getPropertyList failed: " + root(t));
        }

        try { carCls.getMethod("disconnect").invoke(car); } catch (Throwable ignored) { }
        done();
    }

    private void describeConfig(Object cpm, Object cfg) {
        try {
            Class<?> c = cfg.getClass();
            int id = (Integer) c.getMethod("getPropertyId").invoke(cfg);
            int access = (Integer) c.getMethod("getAccess").invoke(cfg);
            int[] areas = (int[]) c.getMethod("getAreaIds").invoke(cfg);
            String name = propName(id);
            String temp = name.toUpperCase(Locale.ROOT).contains("TEMP") ? "  <== TEMP" : "";
            out.line(String.format(Locale.ROOT, "0x%08x %s access=%d areas=%s%s",
                    id, name, access, fmt(areas), temp));
            int area = (areas != null && areas.length > 0) ? areas[0] : 0;
            out.line("    value: " + readValue(cpm, id, area));
        } catch (Throwable t) {
            out.line("  config error: " + root(t));
        }
    }

    private void readOne(Object cpm, String label, int id, int area) {
        out.line(label + String.format(Locale.ROOT, " (0x%08x)", id) + " = " + readValue(cpm, id, area));
    }

    /** Calls CarPropertyManager.getProperty(int, int) and unwraps CarPropertyValue. */
    private String readValue(Object cpm, int id, int area) {
        Future<String> f = pool.submit(() -> {
            Method m = cpm.getClass().getMethod("getProperty", int.class, int.class);
            Object v = m.invoke(cpm, id, area);
            if (v == null) return "null";
            Object value = v.getClass().getMethod("getValue").invoke(v);
            String status = "";
            try { status = " status=" + v.getClass().getMethod("getStatus").invoke(v); } catch (Throwable ignored) { }
            return fmt(value) + status;
        });
        try {
            return f.get(2000, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            f.cancel(true);
            return "ERR " + root(t);
        }
    }

    private String propName(int id) {
        Class<?> ids = loadClass("android.car.VehiclePropertyIds");
        if (ids != null) {
            try { return (String) ids.getMethod("toString", int.class).invoke(null, id); } catch (Throwable ignored) { }
        }
        return "?";
    }

    private Class<?> loadClass(String name) {
        try { return Class.forName(name); } catch (Throwable ignored) { }
        try {
            if (loader == null) {
                loader = new PathClassLoader("/system/framework/android.car.jar", ctx.getClassLoader());
            }
            return Class.forName(name, true, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    private void done() {
        out.line("");
        out.line("=== DONE ===");
        pool.shutdownNow();
    }

    private static String fmt(Object v) {
        if (v == null) return "null";
        if (v.getClass().isArray()) {
            StringBuilder sb = new StringBuilder("[");
            int n = Array.getLength(v);
            for (int i = 0; i < n && i < 16; i++) {
                if (i > 0) sb.append(", ");
                sb.append(Array.get(v, i));
            }
            if (n > 16) sb.append(", ...");
            return sb.append("]").toString();
        }
        return String.valueOf(v);
    }

    private static String root(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
