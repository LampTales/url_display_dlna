package com.cloudmusic2ktv.dlna;

import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.util.Log;
import java.util.function.Consumer;
import android.net.Uri;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-local session; a stopped Activity is never retained. */
final class CastSession {
    static final CastSession INSTANCE = new CastSession();
    interface Listener { void changed(); }
    interface Action { void run() throws Exception; }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Listener listener;
    DlnaRenderer renderer;
    DlnaRenderer.PlaybackInfo info;
    String mediaUrl = "";
    String message = "选择设备后开始投屏";
    boolean busy, reachable;
    boolean relaying;
    private Application context;
    private RelayService service;
    private HttpRelay relay;
    private ServiceConnection binding;
    private boolean stopRequested;
    private int generation;
    private boolean polling;
    private boolean playbackStarted;
    private int failures;
    private final Runnable tick = this::poll;

    void initialize(Context value) { context = (Application) value.getApplicationContext(); }

    void attach(Listener value) { listener = value; publish(); schedule(); }
    void detach(Listener value) {
        if (listener == value) { listener = null; main.removeCallbacks(tick); }
    }

    void cast(DlnaRenderer target, String url, boolean useRelay) {
        castRequest(target, url, useRelay, owner -> owner.prepare(url, target.controlUrl));
    }

    void castLocal(DlnaRenderer target, Uri uri, String mime) {
        if (uri == null) return;
        castRequest(target, uri.toString(), true, owner -> owner.prepareLocal(uri, mime, target.controlUrl));
    }

    private interface RelayFactory { HttpRelay create(RelayService owner) throws Exception; }

    private void castRequest(DlnaRenderer target, String displayUrl, boolean useRelay, RelayFactory factory) {
        if (busy) return;
        if (renderer != null && !renderer.controlUrl.equals(target.controlUrl)) {
            message = "请先停止并断开当前设备，再切换到其他设备";
            publish(); return;
        }
        if (useRelay && service == null) {
            busy = true; message = "正在启动手机中转…"; publish();
            int version = generation;
            binding = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    if (version != generation) return;
                    service = ((RelayService.LocalBinder) binder).service();
                    busy = false; castRequest(target, displayUrl, true, factory);
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    if (binding == this) relayLost(service);
                }
            };
            try {
                context.startForegroundService(new Intent(context, RelayService.class));
                if (!context.bindService(new Intent(context, RelayService.class), binding, Context.BIND_AUTO_CREATE))
                    throw new IllegalStateException("无法连接中转服务");
            } catch (RuntimeException e) {
                busy = false; releaseRelay(); message = "无法启动手机中转：" + e.getClass().getSimpleName(); publish();
            }
            return;
        }
        renderer = target; mediaUrl = displayUrl; info = null; reachable = false; playbackStarted = false;
        RelayService owner = service;
        HttpRelay[] candidate = new HttpRelay[1];
        command("正在投送视频…", () -> {
            if (useRelay) candidate[0] = factory.create(owner);
            target.play(useRelay ? candidate[0].url() : displayUrl);
        }, false, error -> {
            if (error == null) {
                playbackStarted = true;
                if (useRelay) {
                    owner.discard(relay); relay = candidate[0]; relaying = true;
                } else releaseRelay();
            } else {
                if (owner != null) owner.discard(candidate[0]);
                if (relay == null) releaseRelay();
            }
        });
    }

    void pauseOrResume() {
        if (renderer == null || info == null) return;
        DlnaRenderer target = renderer;
        boolean pause = "PLAYING".equals(info.state);
        command(pause ? "正在暂停…" : "正在继续播放…", () -> {
            if (pause) target.pause(); else target.resume();
        }, false);
    }

    void seek(long seconds) {
        if (renderer == null) return;
        DlnaRenderer target = renderer;
        command("正在调整进度…", () -> target.seek(seconds), false);
    }

    void stop() {
        if (renderer == null) return;
        DlnaRenderer target = renderer;
        command("正在停止并断开…", target::stop, true);
    }

    void stopFromNotification() {
        if (busy) { stopRequested = true; return; }
        if (renderer == null) { releaseRelay(); return; }
        DlnaRenderer target = renderer;
        command("正在停止并断开…", target::stop, true, error -> { if (error != null) forget(); });
    }

    void relayLost(RelayService lost) {
        if (lost == null || service != lost) return;
        generation++; busy = false; renderer = null; info = null; reachable = false;
        playbackStarted = false;
        main.removeCallbacks(tick); releaseRelay();
        message = "Wi-Fi 已断开或地址变化，手机中转已停止，请重新连接后投屏"; publish();
    }

    private void releaseRelay() {
        // Service destruction closes both active transfers and any pending candidate endpoint.
        if (binding != null) {
            try { context.unbindService(binding); } catch (IllegalArgumentException ignored) { }
            binding = null;
        }
        if (context != null) context.stopService(new Intent(context, RelayService.class));
        service = null; relay = null; relaying = false;
    }

    void forget() {
        if (busy) return;
        main.removeCallbacks(tick);
        boolean wasRelaying = relaying;
        generation++; releaseRelay();
        renderer = null; info = null; reachable = false;
        playbackStarted = false;
        message = wasRelaying ? "已断开控制并停止手机中转" : "已断开控制；设备上的播放可能仍在继续";
        publish();
    }

    private void command(String progress, Action action, boolean disconnect) {
        command(progress, action, disconnect, error -> { });
    }

    private void command(String progress, Action action, boolean disconnect, Consumer<String> completion) {
        if (busy) return;
        int version = generation;
        busy = true; main.removeCallbacks(tick); message = progress; publish();
        worker.execute(() -> {
            String error = null;
            try { action.run(); }
            catch (Exception e) {
                Log.w("UrlDisplayDlna", "Control failed: " + e.getClass().getSimpleName());
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String result = error;
            main.post(() -> {
                if (version != generation) return;
                busy = false;
                if (result == null && disconnect) {
                    releaseRelay();
                    renderer = null; info = null; reachable = false;
                    playbackStarted = false;
                    message = "已停止并断开，可以选择其他视频或设备";
                } else {
                    message = result == null ? "命令已发送，正在确认播放状态…" : "操作失败：" + result;
                }
                completion.accept(result);
                publish(); schedule();
                if (stopRequested) { stopRequested = false; stopFromNotification(); }
            });
        });
    }

    private void schedule() {
        main.removeCallbacks(tick);
        if (listener != null && renderer != null && !busy && !polling) main.postDelayed(tick, 1000);
    }

    private void poll() {
        if (renderer == null || busy || polling || listener == null) return;
        DlnaRenderer target = renderer;
        polling = true;
        worker.execute(() -> {
            DlnaRenderer.PlaybackInfo value = null;
            String error = null;
            try { value = target.getPlaybackInfo(); }
            catch (Exception e) { error = e.getMessage(); }
            final DlnaRenderer.PlaybackInfo result = value;
            final String failure = error;
            main.post(() -> {
                polling = false;
                // Queries queued before a command must not overwrite its UI.
                if (renderer == target && !busy) {
                    reachable = result != null;
                    if (result != null) {
                        if ("PLAYING".equals(result.state) || "PAUSED_PLAYBACK".equals(result.state)) {
                            playbackStarted = true;
                        }
                        if (playbackStarted && "STOPPED".equals(result.state)) {
                            playbackStarted = false;
                            releaseRelay();
                            renderer = null; info = null; reachable = false;
                            message = "播放已结束";
                            publish();
                            return;
                        }
                        info = result; failures = 0;
                        if (!message.startsWith("操作失败：")) message = "";
                    } else {
                        failures++;
                        message = "无法读取设备状态（第 " + failures + " 次）：" + failure;
                    }
                    publish();
                }
                schedule();
            });
        });
    }

    private void publish() { if (listener != null) listener.changed(); }
}
