package ua.probe.byd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the temperature service when the head unit boots. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        TempWidget.startService(ctx);
    }
}
