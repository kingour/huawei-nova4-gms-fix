package cn.kingour.googleprobe;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, detail, google, proxy, events;
    private boolean active, registered, closing;
    private String previousEvent = "";
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!active || closing) return;
            refreshProxy();
            handler.postDelayed(this, 2000);
        }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            render(intent.getStringExtra("state"), intent.getStringExtra("detail"),
                    intent.getStringExtra("google"));
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        root.setPadding(dp(24), dp(24), dp(24), dp(28));
        scroll.addView(root);
        TextView title = label("Google 联网修复", 27, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        root.addView(label("适用于这台 nova4 · 无需 Root", 14, 0xFFADC0D0));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF1C2A37);
        background.setCornerRadius(dp(18));
        card.setBackground(background);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(24);
        root.addView(card, cardParams);
        status = label("准备检查", 21, Color.WHITE);
        detail = label("正在检查权限与 NekoBox。", 15, 0xFFCAD6E1);
        google = label("mIsGoogleConnectOk = unknown", 17, 0xFF94C7FF);
        google.setTypeface(Typeface.MONOSPACE);
        proxy = label("系统代理：读取中", 14, 0xFFADC0D0);
        card.addView(status); card.addView(detail); card.addView(google); card.addView(proxy);

        root.addView(label("请保持 NekoBox 运行。启动时会短暂重连 Wi-Fi；看到“修复代理已启用”或“Google 检测通过”后，可尝试切换到 Google 登录。", 16, 0xFFDDE6ED));
        root.addView(label("切换应用、按 Home 或返回后继续在后台运行。请使用下方按钮或通知栏的停止按钮清理代理。强行停止可能留下系统代理，请重新打开本应用恢复。", 14, 0xFFADC0D0));
        Button stop = new Button(this);
        stop.setText("停止并恢复网络，退出");
        stop.setAllCaps(false);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { closeAndFinish(); }
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(58));
        buttonParams.topMargin = dp(18);
        root.addView(stop, buttonParams);
        root.addView(label("运行记录", 17, Color.WHITE));
        events = label("", 13, 0xFFAFC0D0);
        events.setTextIsSelectable(true);
        root.addView(events);
        setContentView(scroll);
        root.requestFocus();
    }

    @Override protected void onStart() {
        super.onStart();
        registerReceiver(receiver, new IntentFilter(ProbeService.ACTION_UPDATE));
        registered = true;
    }

    @Override protected void onResume() {
        super.onResume();
        closing = false;
        active = true;
        if (!hasPermissions()) {
            render("需要首次授权", "请连接电脑完成一次 ADB 授权后重新打开。\n\n"
                    + "adb shell pm grant cn.kingour.googleprobe android.permission.WRITE_SECURE_SETTINGS\n\n"
                    + "adb shell pm grant cn.kingour.googleprobe android.permission.DUMP", "unknown");
            return;
        }
        startForegroundService(new Intent(this, ProbeService.class).setAction(ProbeService.ACTION_START));
        handler.removeCallbacks(heartbeat);
        handler.postDelayed(heartbeat, 1000);
    }

    @Override protected void onStop() {
        active = false;
        handler.removeCallbacks(heartbeat);
        if (registered) { unregisterReceiver(receiver); registered = false; }
        super.onStop();
    }

    @Override public void onBackPressed() { super.onBackPressed(); }

    private void closeAndFinish() {
        closing = true;
        active = false;
        handler.removeCallbacks(heartbeat);
        stopRepair();
        finishAndRemoveTask();
    }

    private void stopRepair() {
        try { ProxySettings.clearOwned(this); } catch (Exception ignored) { }
        stopService(new Intent(this, ProbeService.class));
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
    }

    private void render(String state, String message, String value) {
        if (state != null) status.setText(state);
        if (message != null) detail.setText(message);
        String result = "true".equals(value) ? "true" : "false".equals(value) ? "false" : "unknown";
        google.setText("mIsGoogleConnectOk = " + result);
        google.setTextColor("true".equals(result) ? 0xFF86E0B2 : 0xFF94C7FF);
        String event = (state == null ? "" : state) + " · " + result;
        if (!event.equals(previousEvent)) {
            previousEvent = event;
            String history = events.getText().toString();
            if (history.length() > 2500) history = history.substring(history.length() - 1800);
            events.setText(history + (history.isEmpty() ? "" : "\n")
                    + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date())
                    + "  " + event);
        }
        refreshProxy();
    }

    private void refreshProxy() {
        String current = Settings.Global.getString(getContentResolver(), "http_proxy");
        proxy.setText("系统代理：" + (current == null || ":0".equals(current) ? "未设置" : current));
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(4), 1f); view.setPadding(0, dp(9), 0, dp(9));
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
