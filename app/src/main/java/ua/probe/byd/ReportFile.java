package ua.probe.byd;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Saves a text report to Downloads, the app folder and the root of any USB stick. */
public class ReportFile {

    @SuppressWarnings("deprecation")
    public static String save(Context ctx, String name, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder where = new StringBuilder();
        File[] targets = {
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name),
            new File(ctx.getExternalFilesDir(null), name),
        };
        for (File f : targets) if (write(f, data)) where.append(f.getAbsolutePath()).append('\n');
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self")) continue;
                File f = new File(v, name);
                if (write(f, data)) where.append(f.getAbsolutePath()).append('\n');
            }
        }
        return where.length() > 0 ? "\nЗбережено:\n" + where : "\nНе вдалося зберегти файл — сфотографуйте екран.";
    }

    private static boolean write(File f, byte[] data) {
        try {
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
