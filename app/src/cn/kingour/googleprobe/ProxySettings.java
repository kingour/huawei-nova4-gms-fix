package cn.kingour.googleprobe;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/** Owns only this application's loopback proxy, never a user's unrelated proxy. */
public final class ProxySettings {
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 31808;
    public static final String ADDRESS = HOST + ":" + PORT;
    private static final String PREFS = "proxy_ownership";

    private ProxySettings() { }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String get(Context context, String name) {
        String value = Settings.Global.getString(context.getContentResolver(), name);
        return value == null ? "" : value.trim();
    }

    private static boolean emptyLegacy(String value) {
        return value.isEmpty() || ":0".equals(value);
    }

    public static synchronized boolean hasForeignProxy(Context context) {
        String legacy = get(context, "http_proxy");
        String host = get(context, "global_http_proxy_host");
        String port = get(context, "global_http_proxy_port");
        return (!emptyLegacy(legacy) && !ADDRESS.equals(legacy))
                || (!host.isEmpty() && !(HOST.equals(host) && String.valueOf(PORT).equals(port)))
                || !get(context, "global_proxy_pac_url").isEmpty();
    }

    public static synchronized boolean activate(Context context) {
        if (!hasPermission(context) || hasForeignProxy(context)) return false;
        // Commit before mutating persistent system settings, so startup can recover after a crash.
        if (!prefs(context).edit().putBoolean("owned", true).commit()) return false;
        try {
            if (Settings.Global.putString(context.getContentResolver(), "http_proxy", ADDRESS)) {
                return true;
            }
        } catch (RuntimeException ignored) { }
        clearOwned(context);
        return false;
    }

    /** Returns true if there is no owned proxy left, false if permission/conflicts prevent cleanup. */
    public static synchronized boolean clearOwned(Context context) {
        if (!hasPermission(context)) return false;
        try {
            String legacy = get(context, "http_proxy");
            String host = get(context, "global_http_proxy_host");
            String port = get(context, "global_http_proxy_port");
            boolean legacyOwned = ADDRESS.equals(legacy);
            boolean canonicalOwned = HOST.equals(host) && String.valueOf(PORT).equals(port);
            if (hasForeignProxy(context)) {
                // A user may have changed the proxy while this app was running. Do not overwrite it.
                prefs(context).edit().putBoolean("owned", false).commit();
                return !(legacyOwned || canonicalOwned);
            }
            if (legacyOwned || canonicalOwned) {
                // Deleting the key alone does not clear Android 10's live ProxyTracker state.
                if (!Settings.Global.putString(context.getContentResolver(), "http_proxy", ":0")) {
                    return false;
                }
                // Keep the explicit clear sentinel. Immediately deleting this key can race the
                // asynchronous settings observer, which would only see null and skip clearing.
            }
            prefs(context).edit().putBoolean("owned", false).commit();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
