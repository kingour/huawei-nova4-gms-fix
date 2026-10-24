package cn.kingour.googleprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot recovery only: never starts a proxy that the user did not open. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            ProxySettings.clearOwned(context);
        }
    }
}
