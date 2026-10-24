package cn.kingour.googleprobe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import java.io.IOException;
import java.net.InetAddress;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProbeService extends Service {
    public static final String ACTION_START = "cn.kingour.googleprobe.START";
    public static final String ACTION_STOP = "cn.kingour.googleprobe.STOP";
    public static final String ACTION_UPDATE = "cn.kingour.googleprobe.UPDATE";
    public static final int NOTIFICATION_ID = 1;
    public static volatile String latestState = "未启动";
    public static volatile String latestDetail = "打开应用后启动；点击停止清理代理。";
    public static volatile String latestGoogle = "unknown";
    public static volatile boolean running;

    private static final String CHANNEL = "google_probe";
    private static final Pattern GOOGLE = Pattern.compile("mIsGoogleConnectOk=(true|false)");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lifecycle = new Object();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean active;
    private volatile long generation;
    private volatile HttpURLConnection probeConnection;
    private volatile Process dumpProcess;
    private volatile boolean pgReadBlocked;
    private LoopbackProxy proxy;
    private WifiManager wifi;
    private boolean restoreWifi;
    private Future<?> startup;
    private ScheduledFuture<?> monitor;

    @Override public void onCreate() {
        super.onCreate();
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Google 联网修复",
                NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession("已停止", "系统代理已清理，网络恢复原配置。");
        } else if (ACTION_START.equals(action)) {
            startForeground(NOTIFICATION_ID, notification());
            if (!active) startSession();
            else publish(latestState, latestDetail);
        } else {
            stopSession("已停止", "没有有效启动请求。");
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent open = launch == null ? null : PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ProbeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Google 联网修复正在运行")
                .setContentText("后台修复运行中；点击停止可清理系统代理")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止并恢复网络", stop).build())
                .build();
    }

    private void startSession() {
        if (!ProxySettings.hasPermission(this)
                || checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            stopSession("需要一次 ADB 授权", "请授予 WRITE_SECURE_SETTINGS 和 DUMP 权限；未修改系统代理。");
            return;
        }
        if (!ProxySettings.clearOwned(this)) {
            stopSession("无法安全清理", "代理配置存在冲突或写入失败，未启动。");
            return;
        }
        if (ProxySettings.hasForeignProxy(this)) {
            stopSession("检测到其他系统代理", "为保留原设置，本应用不会覆盖它。");
            return;
        }
        final long current;
        synchronized (lifecycle) {
            active = true;
            running = true;
            current = ++generation;
            latestGoogle = "unknown";
        }
        publish("正在准备", "检查 NekoBox 和 Google 实际响应，成功后才启用系统代理。");
        startup = worker.submit(new Runnable() {
            @Override public void run() { initialize(current); }
        });
    }

    private boolean current(long token) { return active && generation == token; }

    private void initialize(final long token) {
        try {
            checkUpstream();
            synchronized (lifecycle) {
                if (!current(token)) return;
                proxy = new LoopbackProxy(new LoopbackProxy.Listener() {
                    @Override public void onFatalError() {
                        fail(token, "代理监听已退出", "已停止修复并尝试清理系统代理。");
                    }
                }, new LoopbackProxy.DirectConnector() {
                    @Override public void connect(Socket socket, String host, int port) throws IOException {
                        Network network = requireVpnNetwork();
                        // Bind the socket and DNS to this VPN; do not silently fall back to Wi-Fi.
                        network.bindSocket(socket);
                        InetAddress[] addresses = network.getAllByName(host);
                        if (addresses.length == 0) throw new IOException("VPN DNS returned no address");
                        socket.connect(new InetSocketAddress(addresses[0], port), 15000);
                    }
                });
                proxy.start();
            }
            verifyGoogle(token);
            synchronized (lifecycle) {
                if (!current(token)) return;
                if (!ProxySettings.activate(this)) throw new Exception("无法设置系统代理");
            }
            if (!current(token)) return;
            publish("检测响应正常", "已取得真实 HTTP 200，正在重连 Wi-Fi 触发系统检测。");
            cycleWifi(token);
            if (!current(token)) return;
            publish("等待系统检测", "系统代理已启用；正在读取 mIsGoogleConnectOk。");
            synchronized (lifecycle) {
                if (!current(token)) return;
                monitor = worker.scheduleWithFixedDelay(new Runnable() {
                    @Override public void run() { poll(token); }
                }, 2, 3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            if (current(token)) fail(token, "操作中断", "已停止修复并尝试恢复网络。");
        } catch (Exception e) {
            fail(token, "启动未完成", safeError(e));
        } finally {
            restoreWifiIfNeeded();
        }
    }

    private Network requireVpnNetwork() throws IOException {
        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network network = connectivity == null ? null : connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            throw new IOException("VPN 未连接，请先启动 NekoBox VPN；未继续修复。");
        }
        return network;
    }

    private void checkUpstream() throws Exception {
        requireVpnNetwork();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 2080), 2000);
        } catch (Exception e) {
            throw new Exception("NekoBox 本地 2080 入口不可用，请先启动 NekoBox。");
        }
    }

    private void verifyGoogle(long token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://www.google.com")
                .openConnection(new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress(ProxySettings.HOST, ProxySettings.PORT)));
        probeConnection = connection;
        try {
            if (!current(token)) throw new InterruptedException();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            int code = connection.getResponseCode();
            // Fixed probe only: log no query strings, headers, bodies or credentials.
            String finalHost = connection.getURL().getHost();
            android.util.Log.i("GoogleProbe", "HTTP probe status=" + code + " finalHost=" + finalHost);
            if (code != 200) throw new Exception("Google 实际响应不是 200（" + code + "，" + finalHost + "），未启用系统代理。");
            try (InputStreamReader input = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                int count = 0;
                while (count < 3 && input.read() != -1) count++;
                if (count < 3) throw new Exception("Google 响应正文过短，未启用系统代理。");
            }
        } finally {
            connection.disconnect();
            if (probeConnection == connection) probeConnection = null;
        }
    }

    private void cycleWifi(long token) throws Exception {
        synchronized (lifecycle) {
            if (!current(token)) return;
            if (wifi == null || !wifi.isWifiEnabled()) return;
            if (!wifi.setWifiEnabled(false)) throw new Exception("系统拒绝关闭 Wi-Fi，已停止修复。");
            restoreWifi = true;
        }
        try { Thread.sleep(1200L); }
        finally { restoreWifiIfNeeded(); }
    }

    private void restoreWifiIfNeeded() {
        synchronized (lifecycle) {
            if (restoreWifi && wifi != null) {
                try {
                    if (wifi.setWifiEnabled(true)) restoreWifi = false;
                } catch (RuntimeException ignored) { }
            }
        }
    }

    private void poll(long token) {
        if (!current(token)) return;
        try {
            checkUpstream();
            if (proxy == null || !proxy.isAlive()) throw new Exception("本地代理已经停止。");
            String result = readGoogleState();
            if (!current(token)) return;
            latestGoogle = result;
            String suffix = "已改写 " + proxy.getRewriteCount() + " 个 Google HTTP 请求。";
            if ("true".equals(result)) publish("Google 检测通过", "mIsGoogleConnectOk=true；" + suffix);
            else if ("false".equals(result)) publish("等待 Google 检测", "mIsGoogleConnectOk=false；" + suffix);
            else publish("修复代理已启用", pgReadBlocked
                    ? "已取得真实 HTTP 200 并完成 Wi-Fi 重连。此机型限制普通应用读取 PG 状态，不能显示真实 true/false。" + suffix
                    : "正在等待 PG 状态，未读到时显示 unknown，不代表 Google 检测失败。" + suffix);
        } catch (Exception e) {
            fail(token, "连接已中断", safeError(e));
        }
    }

    private String readGoogleState() {
        if (pgReadBlocked) return "unknown";
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "powergenius", "DeviceStateService")
                    .redirectErrorStream(true).start();
            dumpProcess = process;
            if (!process.waitFor(2, TimeUnit.SECONDS)) return "unknown";
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            try (InputStream input = process.getInputStream()) {
                int n;
                while (output.size() < 16384 && (n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if (text.contains("Can't find service") || text.contains("Permission Denial")) pgReadBlocked = true;
            Matcher matcher = GOOGLE.matcher(text);
            return matcher.find() ? matcher.group(1) : "unknown";
        } catch (Exception ignored) {
            return "unknown";
        } finally {
            if (process != null) process.destroy();
            if (dumpProcess == process) dumpProcess = null;
        }
    }

    private String safeError(Exception e) {
        // Our own messages contain no URLs except the fixed probe endpoint or user traffic data.
        if (e.getClass() == Exception.class && e.getMessage() != null) return e.getMessage();
        return "网络或系统操作失败（" + e.getClass().getSimpleName() + "），已停止并清理代理。";
    }

    private void fail(final long token, final String state, final String detail) {
        main.post(new Runnable() {
            @Override public void run() { if (current(token)) stopSession(state, detail); }
        });
    }

    private void publish(String state, String detail) {
        latestState = state;
        latestDetail = detail;
        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName());
        update.putExtra("state", state).putExtra("detail", detail)
                .putExtra("google", latestGoogle).putExtra("running", running);
        sendBroadcast(update);
    }

    private boolean cleanup() {
        synchronized (lifecycle) {
            active = false;
            running = false;
            generation++;
            if (startup != null) startup.cancel(true);
            if (monitor != null) monitor.cancel(true);
            // Must remove persistent system routing before closing the listener.
            boolean cleared = ProxySettings.clearOwned(this);
            HttpURLConnection connection = probeConnection;
            if (connection != null) connection.disconnect();
            Process process = dumpProcess;
            if (process != null) process.destroy();
            if (proxy != null) { proxy.close(); proxy = null; }
            restoreWifiIfNeeded();
            return cleared;
        }
    }

    private void stopSession(String state, String detail) {
        boolean cleared = cleanup();
        boolean foreign = false;
        try { foreign = ProxySettings.hasForeignProxy(this); } catch (RuntimeException ignored) { }
        if (!ProxySettings.hasPermission(this)) {
            detail += "（尚无系统设置写权限。）";
        } else if (foreign) {
            detail += " 已保留其他系统代理配置。";
        }
        if (!cleared) detail += " 无法确认本应用代理已清理，请重新打开应用或用 ADB 恢复网络。";
        if (restoreWifi) detail += " Wi-Fi 自动恢复失败，请手动打开 Wi-Fi。";
        publish(state, detail);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Removing the UI task must not interrupt Google login in another app.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        cleanup();
        worker.shutdownNow();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
