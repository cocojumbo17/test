package ua.probe.byd;

import android.content.Context;

/**
 * Gateway to the car's own ADB (127.0.0.1:5555): runs commands as the "shell" user,
 * which may read the BYD climate data a normal app may not.
 * ADB and network errors stay inside this class; callers get a {@link TempReply} or a boolean.
 */
final class CarShell {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    /** Long enough for the user to confirm "Allow USB debugging?" the first time. */
    private static final int AUTH_WAIT_MS = 60_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int GRANT_TIMEOUT_MS = 10_000;

    private final Context ctx;
    private AdbClient adb;

    CarShell(Context ctx) {
        this.ctx = ctx;
    }

    /** Never throws: connection problems come back as a failed reply. */
    TempReply readTemperatures() {
        try {
            String line = runReader();
            return line == null ? TempReply.failure("немає відповіді") : TempReply.parse(line);
        } catch (Exception e) {
            close();
            return TempReply.failure("ADB: " + e.getClass().getSimpleName());
        }
    }

    /** Lets this app draw the floating label; the car has no settings screen for it. */
    boolean grantOverlayPermission() {
        try {
            connection().shell("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW allow",
                    GRANT_TIMEOUT_MS, line -> { });
            return true;
        } catch (Exception e) {
            close();
            return false;
        }
    }

    void close() {
        if (adb != null) adb.close();
        adb = null;
    }

    private String runReader() throws Exception {
        String[] reply = new String[1];
        connection().shell(readerCommand(), READ_TIMEOUT_MS, line -> {
            if (TempReply.isReplyLine(line)) reply[0] = line;
        });
        return reply[0];
    }

    /** Starts {@link ShellMain} from this APK inside a shell-user process. */
    private String readerCommand() {
        return "CLASSPATH=" + ctx.getApplicationInfo().sourceDir
                + " app_process /system/bin " + ShellMain.class.getName() + " read 2>&1";
    }

    private AdbClient connection() throws Exception {
        if (adb == null) {
            adb = AdbClient.connect(HOST, PORT, AdbClient.loadOrCreateKeys(ctx.getFilesDir()), AUTH_WAIT_MS, s -> { });
        }
        return adb;
    }
}
