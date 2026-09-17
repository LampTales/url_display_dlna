package com.cloudmusic2ktv.dlna;

import android.app.Activity;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private static final String LOG_TAG = "UrlDisplayDlna";
    private static final int DISCOVERY_TIMEOUT_MS = 4000;

    private EditText urlInput;
    private LinearLayout deviceList;
    private Button scanButton;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        urlInput = new EditText(this);
        urlInput.setHint("视频 URL（https://…/video.mp4）");
        root.addView(urlInput);

        scanButton = new Button(this);
        scanButton.setText("搜索 DLNA 设备");
        scanButton.setOnClickListener(view -> discover());
        root.addView(scanButton);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceList);
        setContentView(root);

        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            urlInput.setText(intent.getStringExtra(Intent.EXTRA_TEXT));
        }
        Log.i(LOG_TAG, "Activity started; action=" + intent.getAction());
    }

    private void discover() {
        scanButton.setEnabled(false);
        showDeviceMessage("正在搜索…");

        new Thread(() -> {
            // Each search owns its lock so concurrent or recreated activities
            // cannot release another search's lock.
            WifiManager.MulticastLock multicastLock = null;
            try {
                WifiManager wifiManager = (WifiManager) getApplicationContext()
                        .getSystemService(WIFI_SERVICE);
                if (wifiManager == null) {
                    throw new IllegalStateException("当前设备没有可用的 Wi-Fi 服务");
                }
                multicastLock = wifiManager.createMulticastLock("url_display_dlna");
                multicastLock.acquire();

                List<DlnaRenderer> renderers = DlnaRenderer.discover(DISCOVERY_TIMEOUT_MS);
                Log.i(LOG_TAG, "Discovery finished; renderer count=" + renderers.size());
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        showRenderers(renderers);
                    }
                });
            } catch (Exception error) {
                Log.e(LOG_TAG, "DLNA discovery failed", error);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        showDeviceMessage("搜索失败：" + error.getMessage());
                    }
                });
            } finally {
                if (multicastLock != null && multicastLock.isHeld()) {
                    multicastLock.release();
                }
                runOnUiThread(() -> scanButton.setEnabled(true));
            }
        }, "dlna-discovery").start();
    }

    private void showDeviceMessage(String message) {
        deviceList.removeAllViews();
        TextView status = new TextView(this);
        status.setText(message);
        deviceList.addView(status);
    }

    private void showRenderers(List<DlnaRenderer> renderers) {
        deviceList.removeAllViews();
        if (renderers.isEmpty()) {
            showDeviceMessage("没有发现 DLNA 播放设备");
            return;
        }
        for (DlnaRenderer renderer : renderers) {
            Button deviceButton = new Button(this);
            deviceButton.setText(renderer.toString());
            deviceButton.setOnClickListener(view -> play(renderer));
            deviceList.addView(deviceButton);
        }
    }

    private void play(DlnaRenderer renderer) {
        String mediaUrl = urlInput.getText().toString().trim();
        if (mediaUrl.isEmpty()) {
            urlInput.setError("请输入视频 URL");
            return;
        }

        new Thread(() -> {
            try {
                renderer.play(mediaUrl);
                Log.i(LOG_TAG, "DLNA playback commands completed");
                showToast("已发送播放命令", Toast.LENGTH_SHORT);
            } catch (Exception error) {
                Log.e(LOG_TAG, "DLNA playback failed", error);
                showToast("投送失败：" + error.getMessage(), Toast.LENGTH_LONG);
            }
        }, "dlna-playback").start();
    }

    private void showToast(String message, int duration) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                Toast.makeText(this, message, duration).show();
            }
        });
    }
}
