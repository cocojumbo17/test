package ua.probe.byd;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Runs as the shell user (via {@link ShellMain}) and prints one {@link TempReply} line.
 * Read-only: uses the AC device's protected get(deviceType, featureId),
 * which BYD's public API does not expose.
 */
public class TempRead {

    static final int AC_DEVICE_TYPE = 1000;
    // Values from this car's firmware; looked up by name first in case a future update renumbers them.
    static final int FALLBACK_TEMP_INSIDE = 1031798832;
    static final int FALLBACK_TEMP_OUT = 1077936184;

    static void run(Context ctx) {
        try {
            Class<?> acCls = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice");
            Object ac = acCls.getMethod("getInstance", Context.class).invoke(null, new PermissiveContext(ctx));
            Method get = Class.forName("android.hardware.bydauto.AbsBYDAutoDevice")
                    .getDeclaredMethod("get", int.class, int.class);
            get.setAccessible(true);

            int inId = featureId("AC_TEMP_INSIDE", FALLBACK_TEMP_INSIDE);
            int outId = featureId("AC_TEMP_OUT", FALLBACK_TEMP_OUT);
            Object in = get.invoke(ac, AC_DEVICE_TYPE, inId);
            Object out = get.invoke(ac, AC_DEVICE_TYPE, outId);
            System.out.println(TempReply.formatValues(in, out));
        } catch (Throwable t) {
            while (t.getCause() != null) t = t.getCause();
            System.out.println(TempReply.formatError(t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    private static int featureId(String name, int fallback) {
        try {
            Field f = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds$Ac").getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }
}
