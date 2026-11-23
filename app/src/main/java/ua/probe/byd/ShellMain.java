package ua.probe.byd;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Entry point started through ADB as the "shell" user:
 *   CLASSPATH=<this apk> app_process /system/bin ua.probe.byd.ShellMain read
 * Prints one {@link TempReply} line. {@link CarShell} builds this command.
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
            TempRead.run(new ShellContext(systemContext()));
        } catch (Throwable t) {
            System.out.println(TempReply.formatError(t.toString()));
        }
        System.out.flush();
        System.exit(0);
    }

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
