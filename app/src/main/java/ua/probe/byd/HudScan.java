package ua.probe.byd;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import dalvik.system.DexFile;

/**
 * Read-only, runs as shell (ShellMain "hud"): lists BYD API classes, methods and constants
 * related to the HUD, instrument cluster, navigation and media info. Calls nothing.
 */
public class HudScan {

    private static final Pattern CLASS_HIT = Pattern.compile("(?i).*(hud|instrument|cluster|meter|navi|media|display).*");
    private static final Pattern METHOD_HIT = Pattern.compile("(?i).*(hud|navi|media|text|message|display|show|cluster|meter|info).*");

    @SuppressWarnings("deprecation")
    public static void run() {
        System.out.println("=== BYD API classes related to HUD / cluster ===");
        List<String> all = new ArrayList<>();
        try {
            DexFile dex = new DexFile("/system/framework/framework.jar");
            Enumeration<String> en = dex.entries();
            while (en.hasMoreElements()) {
                String c = en.nextElement();
                if (c.startsWith("android.hardware.bydauto.")) all.add(c);
            }
            dex.close();
        } catch (Throwable t) {
            System.out.println("scan failed: " + t);
        }
        System.out.println("(bydauto classes total: " + all.size() + ")");

        for (String cn : all) {
            String simple = cn.substring(cn.lastIndexOf('.') + 1);
            if (!CLASS_HIT.matcher(simple).matches() || simple.contains("FeatureIds")) continue;
            Class<?> c;
            try { c = Class.forName(cn); } catch (Throwable t) { continue; }
            System.out.println("");
            System.out.println("--- " + cn);
            for (Method m : c.getDeclaredMethods()) {
                if (!METHOD_HIT.matcher(m.getName()).matches() && !m.getName().startsWith("set")) continue;
                StringBuilder sb = new StringBuilder("  ").append(Modifier.toString(m.getModifiers())).append(' ')
                        .append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
                Class<?>[] pt = m.getParameterTypes();
                for (int i = 0; i < pt.length; i++) { if (i > 0) sb.append(", "); sb.append(pt[i].getSimpleName()); }
                System.out.println(sb.append(')'));
            }
        }

        System.out.println("");
        System.out.println("=== Constants with HUD in the name ===");
        int n = 0;
        for (String cn : all) {
            Class<?> c;
            try { c = Class.forName(cn); } catch (Throwable t) { continue; }
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getName().toUpperCase(Locale.ROOT).contains("HUD")) continue;
                if (n++ >= 300) continue;
                try {
                    f.setAccessible(true);
                    System.out.println(cn.substring(cn.lastIndexOf('.') + 1) + "." + f.getName() + " = " + f.get(null));
                } catch (Throwable ignored) { }
            }
        }
        System.out.println("(" + n + " HUD constants" + (n > 300 ? ", first 300 shown" : "") + ")");
    }
}
