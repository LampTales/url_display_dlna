package com.cloudmusic2ktv.dlna;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.CheckBox;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.net.Uri;
import android.provider.Settings;
import android.content.ActivityNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String LOG_TAG = "UrlDisplayDlna";
    private final CastSession session = CastSession.INSTANCE;
    private final CastSession.Listener listener = this::render;
    private EditText urlInput;
    private LinearLayout deviceList;
    private Button scanButton, pauseButton, stopButton, castButton, forgetButton;
    private TextView stateText, messageText, timeText;
    private SeekBar seekBar;
    private CheckBox relayMode;
    private CheckBox keepAwake;
    private Button backgroundButton;
    private boolean dragging;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        session.initialize(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);
        // Keep controls clear of the system bars when targeting Android 15.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("视频链接投屏"); title.setTextSize(24); root.addView(title);
        urlInput = new EditText(this);
        urlInput.setId(View.generateViewId());
        urlInput.setHint("HTTP/HTTPS 视频直链");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setSingleLine(true);
        root.addView(urlInput);
        relayMode = new CheckBox(this);
        relayMode.setText("手机中转（适合直链播放失败时）");
        relayMode.setChecked(getPreferences(MODE_PRIVATE).getBoolean("relay", false));
        relayMode.setOnCheckedChangeListener((button, checked) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("relay", checked).apply();
            updateBackgroundOption();
        });
        root.addView(relayMode);
        TextView relayHint = new TextView(this);
        relayHint.setText("默认由屏幕读取原链接。手机中转需保持 Wi-Fi 连接，模式在下次投送时生效。");
        root.addView(relayHint);
        keepAwake = new CheckBox(this);
        keepAwake.setText("中转时保持此页亮屏（避免锁屏限制）");
        keepAwake.setChecked(getPreferences(MODE_PRIVATE).getBoolean("keepAwake", true));
        keepAwake.setOnCheckedChangeListener((button, checked) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("keepAwake", checked).apply();
            updateBackgroundOption();
        });
        root.addView(keepAwake);
        backgroundButton = button(root, "允许后台运行（减少锁屏中断）", this::allowBackground);
        scanButton = button(root, "搜索 DLNA 设备", this::discover);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL); root.addView(deviceList);
        stateText = new TextView(this); stateText.setTextSize(18); root.addView(stateText);
        messageText = new TextView(this); root.addView(messageText);
        timeText = new TextView(this); root.addView(timeText);
        seekBar = new SeekBar(this); seekBar.setContentDescription("播放进度"); root.addView(seekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) timeText.setText(DlnaRenderer.formatTime(progress) + " / "
                        + DlnaRenderer.formatTime(bar.getMax()));
            }
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (session.reachable && !session.busy) session.seek(bar.getProgress());
                render();
            }
        });
        castButton = button(root, "投送输入框中的视频", () -> {
            if (session.renderer != null) play(session.renderer);
        });
        pauseButton = button(root, "暂停", session::pauseOrResume);
        stopButton = button(root, "停止并断开", session::stop);
        forgetButton = button(root, "仅断开控制", () -> new AlertDialog.Builder(this)
                .setMessage("直链视频可能仍在设备上播放；手机中转会停止。断开后可选择其他视频或设备。")
                .setPositiveButton("断开控制", (dialog, which) -> session.forget())
                .setNegativeButton("取消", null).show());
        setContentView(scroll);
        if (saved != null) urlInput.setText(saved.getString("input", ""));
        else if (Intent.ACTION_SEND.equals(getIntent().getAction())) acceptShare(getIntent());
        else urlInput.setText(session.mediaUrl);
        render();
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false);
        button.setOnClickListener(view -> action.run()); parent.addView(button); return button;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); acceptShare(intent);
    }

    private void acceptShare(Intent intent) {
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        Matcher match = Pattern.compile("https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE).matcher(text);
        urlInput.setText(match.find() ? match.group() : text.trim());
    }

    @Override protected void onStart() { super.onStart(); session.attach(listener); }
    @Override protected void onResume() { super.onResume(); updateBackgroundOption(); }
    @Override protected void onStop() { session.detach(listener); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("input", urlInput.getText().toString()); super.onSaveInstanceState(out);
    }

    private void render() {
        if (stateText == null) return;
        DlnaRenderer.PlaybackInfo info = session.info;
        String state = info == null ? "等待状态" : label(info.state);
        if (info != null && "ERROR_OCCURRED".equals(info.status)) state = "设备报告播放错误";
        if (info != null && !session.reachable) state = "状态暂不可用";
        stateText.setText(session.renderer == null ? "未连接设备" : session.renderer + "\n" + state
                + (session.relaying ? " · 手机中转" : " · 直链"));
        relayMode.setEnabled(!session.busy);
        updateBackgroundOption();
        messageText.setText(session.message);
        boolean active = info != null && ("PLAYING".equals(info.state) || "PAUSED_PLAYBACK".equals(info.state));
        pauseButton.setText(info != null && "PAUSED_PLAYBACK".equals(info.state) ? "继续播放" : "暂停");
        pauseButton.setEnabled(!session.busy && session.reachable && active);
        stopButton.setEnabled(!session.busy && session.renderer != null);
        castButton.setEnabled(!session.busy && session.renderer != null);
        forgetButton.setVisibility(session.renderer != null && !session.reachable && !session.busy
                ? View.VISIBLE : View.GONE);
        seekBar.setEnabled(!session.busy && session.reachable && active && info.duration > 0);
        if (!dragging) {
            seekBar.setMax(info == null || info.duration < 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, info.duration));
            seekBar.setProgress(info == null ? 0 : (int) Math.max(0, info.position));
            timeText.setText(info == null ? "--:-- / --:--" : DlnaRenderer.formatTime(info.position)
                    + " / " + DlnaRenderer.formatTime(info.duration));
        }
        for (int i = 0; i < deviceList.getChildCount(); i++) deviceList.getChildAt(i).setEnabled(!session.busy);
    }

    private String label(String state) {
        switch (state) {
            case "PLAYING": return "正在播放";
            case "PAUSED_PLAYBACK": return "已暂停";
            case "STOPPED": return "已停止或播放结束";
            case "TRANSITIONING": return "正在加载";
            case "NO_MEDIA_PRESENT": return "设备没有媒体";
            default: return state;
        }
    }

    private void discover() {
        hideKeyboard(); scanButton.setEnabled(false); showDeviceMessage("正在搜索…");
        new Thread(() -> {
            WifiManager.MulticastLock lock = null;
            try {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifi == null) throw new IOException("没有可用 Wi-Fi");
                lock = wifi.createMulticastLock("url_display_dlna"); lock.acquire();
                List<DlnaRenderer> found = DlnaRenderer.discover(4000);
                Log.i(LOG_TAG, "Discovery finished; renderer count=" + found.size());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    deviceList.removeAllViews();
                    if (found.isEmpty()) showDeviceMessage("没有发现设备，请确认同一 Wi-Fi 后重试");
                    for (DlnaRenderer target : found) button(deviceList, target.toString(), () -> play(target));
                    render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showDeviceMessage("搜索失败：" + error.getMessage());
                });
            } finally {
                if (lock != null && lock.isHeld()) lock.release();
                runOnUiThread(() -> { if (!isDestroyed()) scanButton.setEnabled(true); });
            }
        }, "dlna-discovery").start();
    }

    private void showDeviceMessage(String text) {
        deviceList.removeAllViews(); TextView view = new TextView(this);
        view.setText(text); deviceList.addView(view);
    }

    private void play(DlnaRenderer target) {
        String url = urlInput.getText().toString().trim();
        try { DlnaRenderer.validateUrl(url); }
        catch (IOException error) { urlInput.setError(error.getMessage()); return; }
        if (relayMode.isChecked() && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        hideKeyboard(); session.cast(target, url, relayMode.isChecked());
    }

    private void hideKeyboard() {
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        urlInput.clearFocus();
    }

    private void updateBackgroundOption() {
        if (backgroundButton == null) return;
        keepAwake.setVisibility(relayMode.isChecked() || session.relaying ? View.VISIBLE : View.GONE);
        if (keepAwake.isChecked() && session.relaying)
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PowerManager power = getSystemService(PowerManager.class);
        backgroundButton.setVisibility(relayMode.isChecked() && !power.isIgnoringBatteryOptimizations(getPackageName())
                ? View.VISIBLE : View.GONE);
    }

    private void allowBackground() {
        // Only request an exemption after the user chooses this button: locked-screen LAN
        // serving is a core feature, but it also keeps the phone awake while casting.
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }
}
