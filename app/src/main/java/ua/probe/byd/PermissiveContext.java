package ua.probe.byd;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

/**
 * Context wrapper that answers "granted" to client-side permission checks.
 * Some BYD device classes check permissions inside the app process only;
 * checks done by the car's system service are not affected by this.
 */
public class PermissiveContext extends ContextWrapper {
    public PermissiveContext(Context base) { super(base); }

    @Override public Context getApplicationContext() { return this; }

    @Override public int checkPermission(String p, int pid, int uid) { return PackageManager.PERMISSION_GRANTED; }
    @Override public int checkCallingPermission(String p) { return PackageManager.PERMISSION_GRANTED; }
    @Override public int checkCallingOrSelfPermission(String p) { return PackageManager.PERMISSION_GRANTED; }
    @Override public int checkSelfPermission(String p) { return PackageManager.PERMISSION_GRANTED; }

    @Override public void enforcePermission(String p, int pid, int uid, String msg) { }
    @Override public void enforceCallingPermission(String p, String msg) { }
    @Override public void enforceCallingOrSelfPermission(String p, String msg) { }
}
