package com.cloudmusic2ktv.dlna;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Dedicated playback screen. The casting session itself remains process-local. */
public final class CastDetailActivity extends Activity {
    private final CastSession session = CastSession.INSTANCE;
    private final CastSession.Listener listener = this::render;
    private TextView deviceText, stateText, messageText, timeText;
    private SeekBar seekBar;
    private android.widget.Button pauseButton, stopButton, forgetButton;
    private boolean dragging;
    private boolean hadSession;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Ui.BACKGROUND);
        getWindow().setNavigationBarColor(Ui.BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(Ui.BACKGROUND);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18); root.setPadding(pad, pad, pad, pad); scroll.addView(root);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.label(this, "投屏详情", 24, Ui.BLACK);
        topBar.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        android.widget.Button backButton = Ui.button(this, "返回", view -> finish(), false);
        topBar.addView(backButton, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 44)));
        root.addView(topBar);
        Ui.margin(topBar, 0, 0, 0, Ui.dp(this, 16));

        LinearLayout card = Ui.card(this); root.addView(card);
        deviceText = Ui.heading(this, "未连接设备", 20); card.addView(deviceText);
        stateText = Ui.label(this, "等待投屏", 15, Ui.MUTED); card.addView(stateText);
        Ui.margin(stateText, 0, Ui.dp(this, 6), 0, 0);
        messageText = Ui.label(this, "", 14, Ui.MUTED); card.addView(messageText);

        timeText = Ui.label(this, "--:-- / --:--", 14, Ui.MUTED); root.addView(timeText);
        Ui.margin(timeText, 0, Ui.dp(this, 22), 0, Ui.dp(this, 4));
        seekBar = new SeekBar(this); seekBar.setContentDescription("播放进度"); root.addView(seekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) timeText.setText(DlnaRenderer.formatTime(progress) + " / " + DlnaRenderer.formatTime(bar.getMax()));
            }
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false; if (session.reachable && !session.busy) session.seek(bar.getProgress()); render();
            }
        });

        pauseButton = Ui.button(this, "暂停", view -> session.pauseOrResume(), false); root.addView(pauseButton);
        Ui.margin(pauseButton, 0, Ui.dp(this, 20), 0, Ui.dp(this, 10));
        stopButton = Ui.button(this, "停止并断开", view -> session.stop(), true); root.addView(stopButton);
        forgetButton = Ui.button(this, "仅断开控制", view -> new AlertDialog.Builder(this)
                .setMessage("直链视频可能仍在设备上播放；手机中转会停止。")
                .setPositiveButton("断开控制", (dialog, which) -> session.forget())
                .setNegativeButton("取消", null).show(), false); root.addView(forgetButton);
        Ui.margin(forgetButton, 0, Ui.dp(this, 10), 0, 0);
        hadSession = session.renderer != null;
        setContentView(scroll); render();
    }

    @Override protected void onStart() { super.onStart(); session.attach(listener); }
    @Override protected void onStop() { session.detach(listener); super.onStop(); }

    private void render() {
        if (deviceText == null) return;
        if (session.renderer != null) hadSession = true;
        if (hadSession && session.renderer == null && !session.busy) {
            finish();
            return;
        }
        DlnaRenderer.PlaybackInfo info = session.info;
        boolean active = info != null && ("PLAYING".equals(info.state) || "PAUSED_PLAYBACK".equals(info.state));
        deviceText.setText(session.renderer == null ? "未连接设备" : session.renderer.toString());
        stateText.setText(info == null ? (session.renderer == null ? "等待投屏" : "正在连接…") : label(info.state)
                + (session.relaying ? " · 手机中转" : " · 直链"));
        messageText.setText(session.message == null ? "" : session.message);
        pauseButton.setText(info != null && "PAUSED_PLAYBACK".equals(info.state) ? "继续播放" : "暂停");
        pauseButton.setEnabled(!session.busy && session.reachable && active);
        stopButton.setEnabled(!session.busy && session.renderer != null);
        forgetButton.setVisibility(session.renderer != null && !session.reachable && !session.busy ? View.VISIBLE : View.GONE);
        seekBar.setEnabled(!session.busy && session.reachable && active && info.duration > 0);
        if (!dragging) {
            seekBar.setMax(info == null || info.duration < 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, info.duration));
            seekBar.setProgress(info == null ? 0 : (int) Math.max(0, info.position));
            timeText.setText(info == null ? "--:-- / --:--" : DlnaRenderer.formatTime(info.position) + " / " + DlnaRenderer.formatTime(info.duration));
        }
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
}
