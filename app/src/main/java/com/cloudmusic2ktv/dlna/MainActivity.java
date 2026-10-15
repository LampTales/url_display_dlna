package com.cloudmusic2ktv.dlna;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
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
import android.provider.OpenableColumns;
import android.database.Cursor;

public class MainActivity extends Activity {
    private static final String LOG_TAG = "UrlDisplayDlna";
    private final CastSession session = CastSession.INSTANCE;
    private final CastSession.Listener listener = this::render;
    private EditText urlInput;
    private LinearLayout deviceList;
    private Button scanButton, pauseButton, stopButton, castButton, forgetButton, castingBar;
    private TextView clearButton;
    private TextView stateText, messageText, timeText;
    private SeekBar seekBar;
    private CheckBox relayMode;
    private TextView relayHint;
    private CheckBox keepAwake;
    private Button backgroundButton;
    private Button localButton;
    private Uri localVideo;
    private String localMime;
    private boolean settingLocal;
    private static final int PICK_LOCAL_VIDEO = 41;
    private boolean dragging;

    /**
     * EditText keeps focus when a click lands on an otherwise empty part of a
     * ScrollView because the child hierarchy may consume the touch first. Do
     * this at the Activity boundary so every in-app tap outside the editor
     * behaves like a normal form dismissal.
     */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && urlInput != null
                && urlInput.hasFocus()
                && !containsTouch(urlInput, event)
                && !containsTouch(clearButton, event)) {
            hideKeyboard();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Ui.BACKGROUND);
        getWindow().setNavigationBarColor(Ui.BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        session.initialize(this);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Ui.BACKGROUND);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setFocusableInTouchMode(true);
        root.setOnClickListener(view -> { hideKeyboard(); root.requestFocus(); });
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);
        // Keep controls clear of the system bars when targeting Android 15.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.setClickable(true);
        scroll.setOnClickListener(view -> { hideKeyboard(); root.requestFocus(); });
        scroll.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN
                    && !containsTouch(urlInput, event) && !containsTouch(clearButton, event)) {
                hideKeyboard(); root.requestFocus();
            }
            return false;
        });
        TextView title = Ui.label(this, "视频投屏", 24, Ui.BLACK); root.addView(title);
        Ui.margin(title, 0, 0, 0, Ui.dp(this, 16));
        FrameLayout urlField = new FrameLayout(this);
        urlInput = new EditText(this);
        urlInput.setId(View.generateViewId());
        urlInput.setHint("HTTP/HTTPS 视频直链");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setSingleLine(true);
        urlInput.setTextColor(Ui.BLACK); urlInput.setHintTextColor(Ui.MUTED);
        urlInput.setPadding(0, 0, Ui.dp(this, 40), 0);
        urlInput.setBackgroundColor(Color.TRANSPARENT);
        urlInput.setGravity(Gravity.CENTER_VERTICAL);
        urlInput.setIncludeFontPadding(false);
        urlInput.setMinHeight(0);
        urlInput.setMinimumHeight(0);
        urlField.addView(urlInput, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 30)));
        // Give the editor edge enough contrast to read as one control with its hint.
        View underline = new View(this); underline.setBackgroundColor(Color.rgb(202, 202, 207));
        FrameLayout.LayoutParams lineParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1), Gravity.BOTTOM);
        urlField.addView(underline, lineParams);
        clearButton = Ui.label(this, "×", 24, Ui.MUTED);
        clearButton.setGravity(Gravity.CENTER); clearButton.setContentDescription("清空链接");
        clearButton.setPadding(0, 0, 0, 0);
        clearButton.setVisibility(View.GONE);
        clearButton.setOnClickListener(view -> {
            settingLocal = false;
            localVideo = null;
            localMime = null;
            urlInput.setText("");
            urlInput.requestFocus();
            updateClearVisibility();
        });
        FrameLayout.LayoutParams clearParams = new FrameLayout.LayoutParams(
                Ui.dp(this, 40), Ui.dp(this, 30), Gravity.END | Gravity.CENTER_VERTICAL);
        urlField.addView(clearButton, clearParams);
        root.addView(urlField, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 32)));
        TextView urlHint = Ui.label(this, "分享视频链接，或选择手机本地视频", 13, Ui.MUTED);
        root.addView(urlHint);
        Ui.margin(urlHint, 0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        urlInput.setOnFocusChangeListener((view, focused) -> updateClearVisibility());
        urlInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable value) {
                if (!settingLocal) { localVideo = null; localMime = null; }
                urlInput.setError(null);
                updateClearVisibility();
            }
        });
        localButton = button(root, "选择本地视频", this::chooseLocalVideo);
        relayMode = new CheckBox(this);
        relayMode.setText("手机中转");
        relayMode.setChecked(getPreferences(MODE_PRIVATE).getBoolean("relay", false));
        relayMode.setOnCheckedChangeListener((button, checked) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("relay", checked).apply();
            updateBackgroundOption();
        });
        root.addView(relayMode);
        relayHint = Ui.label(this, "本地视频投屏将自动通过手机中转", 13, Ui.MUTED);
        relayHint.setText("本地视频投屏将自动通过手机中转");
        root.addView(relayHint);
        keepAwake = new CheckBox(this);
        keepAwake.setText("中转时保持常亮");
        keepAwake.setChecked(getPreferences(MODE_PRIVATE).getBoolean("keepAwake", true));
        keepAwake.setOnCheckedChangeListener((button, checked) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("keepAwake", checked).apply();
            updateBackgroundOption();
        });
        root.addView(keepAwake);
        backgroundButton = button(root, "允许后台运行（减少锁屏中断）", this::allowBackground);
        scanButton = Ui.button(this, "搜索 DLNA 设备", view -> discover(), true);
        root.addView(scanButton); Ui.margin(scanButton, 0, Ui.dp(this, 10), 0, 0);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL); deviceList.setBackgroundColor(Ui.SURFACE); root.addView(deviceList);
        castingBar = Ui.button(this, "未连接设备", view -> openCastDetails(), false);
        castingBar.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        castingBar.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        castingBar.setBackground(Ui.background(Ui.SURFACE, 0, Ui.BORDER, Ui.dp(this, 1)));
        castingBar.setVisibility(View.GONE);
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
        stateText.setVisibility(View.GONE); messageText.setVisibility(View.GONE); timeText.setVisibility(View.GONE);
        seekBar.setVisibility(View.GONE); castButton.setVisibility(View.GONE); pauseButton.setVisibility(View.GONE);
        stopButton.setVisibility(View.GONE); forgetButton.setVisibility(View.GONE);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.addView(castingBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
        setContentView(outer);
        if (saved != null) {
            urlInput.setText(saved.getString("input", ""));
            String savedUri = saved.getString("localUri", null);
            if (savedUri != null) { try { localVideo = Uri.parse(savedUri); localMime = getContentResolver().getType(localVideo); } catch (RuntimeException ignored) { } }
        }
        else if (Intent.ACTION_SEND.equals(getIntent().getAction())) acceptShare(getIntent());
        else urlInput.setText(session.mediaUrl);
        render();
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = Ui.button(this, text, view -> action.run(), false);
        parent.addView(button); Ui.margin(button, 0, Ui.dp(this, 8), 0, 0); return button;
    }

    private void openCastDetails() {
        startActivity(new Intent(this, CastDetailActivity.class));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); acceptShare(intent);
    }

    private void acceptShare(Intent intent) {
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        Matcher match = Pattern.compile("https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE).matcher(text);
        localVideo = null; localMime = null;
        urlInput.setText(match.find() ? match.group() : text.trim());
    }

    @Override protected void onStart() { super.onStart(); session.attach(listener); }
    @Override protected void onResume() { super.onResume(); updateBackgroundOption(); }
    @Override protected void onStop() { session.detach(listener); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("input", urlInput.getText().toString());
        if (localVideo != null) out.putString("localUri", localVideo.toString());
        super.onSaveInstanceState(out);
    }

    private void render() {
        if (stateText == null) return;
        DlnaRenderer.PlaybackInfo info = session.info;
        String state = info == null ? "等待状态" : label(info.state);
        if (info != null && "ERROR_OCCURRED".equals(info.status)) state = "设备报告播放错误";
        if (info != null && !session.reachable) state = "状态暂不可用";
        stateText.setText(session.renderer == null ? "未连接设备" : session.renderer + "\n" + state
                + (session.relaying ? " · 手机中转" : " · 直链"));
        if (castingBar != null) {
            castingBar.setVisibility(session.renderer == null ? View.GONE : View.VISIBLE);
            castingBar.setText(session.renderer == null ? "未连接设备" : "投屏中 · " + session.renderer);
            castingBar.setTextColor(Ui.BLACK);
        }
        relayMode.setEnabled(!session.busy);
        relayMode.setText("手机中转");
        relayHint.setText("本地视频投屏将自动通过手机中转");
        localButton.setEnabled(!session.busy);
        updateClearVisibility();
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
                    for (int i = 0; i < found.size(); i++) {
                        DlnaRenderer target = found.get(i);
                        TextView row = Ui.listRow(this, target.toString(), view -> play(target));
                        deviceList.addView(row);
                        if (i + 1 < found.size()) {
                            View divider = new View(this); divider.setBackgroundColor(Ui.BORDER);
                            deviceList.addView(divider, new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
                        }
                    }
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
        if (localVideo != null) {
            hideKeyboard(); session.castLocal(target, localVideo, localMime); openCastDetails();
            return;
        }
        String url = urlInput.getText().toString().trim();
        try { DlnaRenderer.validateUrl(url); }
        catch (IOException error) { urlInput.setError(error.getMessage()); return; }
        if (relayMode.isChecked() && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        hideKeyboard(); session.cast(target, url, relayMode.isChecked()); openCastDetails();
    }

    private void chooseLocalVideo() {
        hideKeyboard();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_LOCAL_VIDEO);
    }

    @SuppressLint("WrongConstant")
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_LOCAL_VIDEO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri chosen = data.getData();
        int persistFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { if (persistFlags != 0) getContentResolver().takePersistableUriPermission(chosen, persistFlags); }
        catch (SecurityException ignored) { }
        localVideo = chosen; localMime = getContentResolver().getType(chosen);
        String name = chosen.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(chosen, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) { }
        settingLocal = true; urlInput.setText("本地视频：" + (name == null ? "已选择文件" : name)); settingLocal = false;
        urlInput.clearFocus(); render();
    }

    private void updateClearVisibility() {
        if (clearButton == null || urlInput == null) return;
        boolean visible = urlInput.hasFocus() && urlInput.length() > 0 && !session.busy;
        clearButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private boolean containsTouch(View target, MotionEvent event) {
        if (target == null || target.getVisibility() != View.VISIBLE) return false;
        Rect bounds = new Rect();
        target.getGlobalVisibleRect(bounds);
        return bounds.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
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
