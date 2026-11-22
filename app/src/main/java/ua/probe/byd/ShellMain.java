package ua.probe.byd;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Entry point started through ADB as the "shell" user:
 *   CLASSPATH=<this apk> app_process /system/bin ua.probe.byd.ShellMain
 * It builds a system Context and runs the same read-only Probe with shell privileges.
 */
public class ShellMain {

    /** Context that presents itself as the shell package, matching the shell UID. */
    static class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public Context getApplicationContext() { return this; }
    }

    public static void main(String[] args) {
        try {
            System.out.println("ShellMain uid=" + android.os.Process.myUid());
            Context ctx = new ShellContext(systemContext());
            if (args.length > 0 && args[0].equals("read")) {
                TempRead.run(ctx);
            } else if (args.length > 0 && args[0].equals("temps")) {
                new TempProbe(ctx, s -> System.out.println(s)).run();
            } else {
                new Probe(ctx, s -> System.out.println(s)).run();
            }
        } catch (Throwable t) {
            System.out.println("ShellMain failed: " + t);
            t.printStackTrace(System.out);
        }
        System.out.flush();
        System.exit(0);
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    static Context systemContext() throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> c = at.getDeclaredConstructor();
        c.setAccessible(true);
        Object thread = c.newInstance();
        Field cur = at.getDeclaredField("sCurrentActivityThread");
        cur.setAccessible(true);
        cur.set(null, thread);
        Method gsc = at.getDeclaredMethod("getSystemContext");
        gsc.setAccessible(true);
        return (Context) gsc.invoke(thread);
    }
}
