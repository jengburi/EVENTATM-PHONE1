package com.jengburi.glassesai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.view.KeyEvent;

/**
 * 백그라운드에서 안경 연결을 유지하며 버튼을 기다리는 서비스.
 * - 안경 BLE 신호(기본 0x97 = AI 버튼)로 실행
 * - 안경 터치가 보내는 재생/일시정지 버튼으로 실행
 */
public class GlassesService extends Service {
    static final String CH = "glasses";
    public static volatile boolean running;
    private volatile boolean stop;
    private MediaSession session;

    public static void start(Context c) {
        Intent i = new Intent(c, GlassesService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    public static void stop(Context c) { c.stopService(new Intent(c, GlassesService.class)); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "안경 버튼 대기", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("안경 AI 대기 중")
                .setContentText("안경 버튼을 누르면 찍고 설명해 줘요")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 30) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            startForeground(1, n, type);
        } else if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(1, n);
        running = true;
        AppCore core = AppCore.get(this);
        core.log("안경 버튼 대기 시작");
        if (core.prefs.getBoolean("mediaKey", true)) setupMediaButton(core);
        new Thread(this::keepConnected).start();
        if (core.prefs.getBoolean("wakeOn", false)) core.wakeWord.start();
    }

    private void keepConnected() {
        AppCore core = AppCore.get(this);
        int tick = 0, lastShown = -2;
        boolean warned = false;
        while (!stop) {
            try {
                if (!core.ble.isConnected() && !core.busy) core.ble.connect();
                if (core.ble.isConnected() && !core.busy && tick % 6 == 0) core.ble.requestBattery(); // 1분마다
            } catch (Exception e) {
                core.log("안경 연결 대기: " + e.getMessage());
            }
            int b = core.ble.battery;
            if (b != lastShown) { lastShown = b; updateNotification(b, core.ble.charging); }
            if (b >= 0 && b <= 15 && !core.ble.charging && !warned && !core.busy) { warned = true; core.say("안경 배터리가 " + b + "퍼센트 남았어요"); }
            if (b > 20 || core.ble.charging) warned = false;
            tick++;
            try { Thread.sleep(10000); } catch (InterruptedException ignored) { }
        }
    }

    private void updateNotification(int battery, boolean charging) {
        try {
            String bt = battery < 0 ? "" : " · 🔋" + battery + "%" + (charging ? " 충전 중" : "");
            PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CH)
                    .setContentTitle("안경 AI 대기 중" + bt)
                    .setContentText("안경 버튼을 누르면 찍고 설명해 줘요")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).build();
            getSystemService(NotificationManager.class).notify(1, n);
        } catch (Exception ignored) { }
    }

    private void setupMediaButton(AppCore core) {
        session = new MediaSession(this, "glasses-ai");
        session.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent e = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (e != null && e.getAction() == KeyEvent.ACTION_DOWN) {
                    int k = e.getKeyCode();
                    core.log("재생 버튼 신호: " + KeyEvent.keyCodeToString(k));
                    if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_HEADSETHOOK
                            || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                        core.action("안경 터치", AppCore.parseAction(core.prefs.getString("actPlay", "")));
                        return true;
                    }
                    if (k == KeyEvent.KEYCODE_MEDIA_NEXT) {
                        core.action("안경 다음 버튼", AppCore.parseAction(core.prefs.getString("actNext", "번역")));
                        return true;
                    }
                    if (k == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                        core.action("안경 이전 버튼", AppCore.parseAction(core.prefs.getString("actPrev", "다시듣기")));
                        return true;
                    }
                }
                return super.onMediaButtonEvent(intent);
            }
        });
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PAUSED, 0, 1f).build());
        session.setActive(true);
        playSilence(); // 미디어 버튼이 이 앱으로 오도록 아주 짧게 무음 재생
    }

    private void playSilence() {
        new Thread(() -> {
            try {
                int rate = 16000;
                short[] buf = new short[rate / 2];
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(buf.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
                t.write(buf, 0, buf.length);
                t.play();
                Thread.sleep(700);
                t.release();
            } catch (Exception ignored) { }
        }).start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        stop = true;
        running = false;
        if (session != null) session.release();
        AppCore.get(this).wakeWord.stop();
        AppCore.get(this).log("안경 버튼 대기 종료");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
