package ua.probe.byd;

import android.content.Context;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * EXPERIMENT, runs as shell (ShellMain "addr" / "clear"), only when the user presses the button:
 * sends one text line to the instrument cluster through BYDAutoInstrumentDevice.sendAddressInfo(int, String),
 * or calls clearInfo(int). Prints "RESULT=<code>".
 */
public class ClusterText {

    public static void run(Context ctx, String[] args) {
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Object ins = cls.getMethod("getInstance", Context.class).invoke(null, new PermissiveContext(ctx));
            int type = Integer.parseInt(args[1]);
            Object r;
            if (args[0].equals("addr")) {
                String text = new String(Base64.getUrlDecoder().decode(args[2]), StandardCharsets.UTF_8);
                System.out.println("sendAddressInfo(" + type + ", \"" + text + "\")");
                Method m = cls.getMethod("sendAddressInfo", int.class, String.class);
                r = m.invoke(ins, type, text);
            } else {
                System.out.println("clearInfo(" + type + ")");
                Method m = cls.getMethod("clearInfo", int.class);
                r = m.invoke(ins, type);
            }
            System.out.println("RESULT=" + r);
        } catch (Throwable t) {
            while (t.getCause() != null) t = t.getCause();
            System.out.println("RESULT=error " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
