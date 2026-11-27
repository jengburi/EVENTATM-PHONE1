package com.jengburi.glassesai;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * 휴대폰 마이크로 계속 듣다가 호출어("쟁부리야")가 들리면 '그냥 물어보기'를 실행.
 * "쟁부리야 오늘 날씨 어때"처럼 이어서 말하면 뒤의 질문을 바로 사용.
 */
public class WakeWord {
    private final AppCore core;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private volatile boolean running;
    private boolean muted;
    private volatile boolean preferOffline = true;
    /** 마지막으로 알아들은 말 (화면 표시용) */
    public volatile String lastHeard = "";

    WakeWord(AppCore core) { this.core = core; }

    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        if (!SpeechRecognizer.isRecognitionAvailable(core.ctx)) { core.log("이 휴대폰은 음성 인식을 지원하지 않아 호출어를 쓸 수 없어요"); return; }
        running = true;
        core.log("호출어 대기 시작: \"" + word() + "\"");
        main.post(this::listen);
    }

    public void stop() {
        running = false;
        main.post(() -> {
            if (sr != null) { try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { } sr = null; }
            mute(false);
        });
        core.log("호출어 대기 끝");
    }

    /** 다른 작업이 마이크를 써야 할 때 즉시 듣기 멈춤 (작업이 끝나면 listen() 이 알아서 다시 시작) */
    public void pauseNow() {
        if (!running) return;
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        main.post(() -> {
            if (sr != null) { try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { } sr = null; }
            mute(false);
            done.countDown();
        });
        try { done.await(1, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        try { Thread.sleep(300); } catch (InterruptedException ignored) { } // 마이크가 완전히 풀릴 시간
    }

    String word() { return core.prefs.getString("wakeWord", "쟁부리야"); }

    /** 비교용: 띄어쓰기·끝의 '야/아' 빼기 */
    static String norm(String s) { return s == null ? "" : s.replace(" ", "").replace(",", "").replace(".", ""); }

    /** 인식 결과에서 호출어를 찾으면 그 뒤의 말을 돌려줌 (없으면 null) */
    String match(String heard) {
        String h = norm(heard);
        String w = norm(word());
        String base = w.endsWith("야") || w.endsWith("아") ? w.substring(0, w.length() - 1) : w;
        java.util.List<String> keys = new ArrayList<>();
        keys.add(base);
        if (base.equals("쟁부리")) { keys.add("장부리"); keys.add("잼부리"); keys.add("젱부리"); keys.add("쟁불이"); keys.add("쟁부이"); keys.add("쟁보리"); }
        // 기본 호출어가 '쟁부리'면 '○부리'로 들려도 인정 (장부리·챙부리·생부리 등)
        if (base.equals("쟁부리")) {
            int i = h.indexOf("부리");
            if (i >= 1) {
                String rest = h.substring(i + 2);
                if (rest.startsWith("야") || rest.startsWith("아")) rest = rest.substring(1);
                return rest;
            }
            int j = h.indexOf("부이");
            if (j >= 1 && (h.charAt(j - 1) == '쟁' || h.charAt(j - 1) == '장')) {
                String rest = h.substring(j + 2);
                if (rest.startsWith("야") || rest.startsWith("아")) rest = rest.substring(1);
                return rest;
            }
        }
        for (String k : keys) {
            int i = h.indexOf(k);
            if (i >= 0) {
                String rest = h.substring(i + k.length());
                if (rest.startsWith("야") || rest.startsWith("아")) rest = rest.substring(1);
                return rest;
            }
        }
        return null;
    }

    private void mute(boolean on) {
        // 음성 인식 시작/끝 '띵' 소리 줄이기 (알림·시스템 소리만)
        try {
            AudioManager am = (AudioManager) core.ctx.getSystemService(Context.AUDIO_SERVICE);
            if (on == muted) return;
            int dir = on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0);
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0);
            muted = on;
        } catch (Exception ignored) { }
    }

    private void listen() {
        if (!running) return;
        // 다른 작업(대화·통역·사진) 중이거나 말하는 중이면 잠시 쉬기
        if (core.busy || core.isSpeaking()) { mute(false); main.postDelayed(this::listen, 1000); return; }
        try {
            if (sr == null) {
                sr = SpeechRecognizer.createSpeechRecognizer(core.ctx);
                sr.setRecognitionListener(listener);
            }
            mute(true);
            Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
            it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            it.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);
            it.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200);
            sr.startListening(it);
        } catch (Exception e) {
            main.postDelayed(this::listen, 2000);
        }
    }

    private boolean handle(Bundle b, boolean finalResult) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r == null) return false;
        if (!r.isEmpty() && r.get(0) != null && !r.get(0).trim().isEmpty()) lastHeard = r.get(0);
        for (String heard : r) {
            String rest = match(heard);
            if (rest == null) continue;
            // 이어서 질문까지 말했으면 끝까지 듣고, 호출어만이면 바로 깨우기
            if (!finalResult && rest.trim().isEmpty()) return false;
            if (!finalResult) return false;
            try { sr.cancel(); } catch (Exception ignored) { }
            mute(false);
            core.log("호출어 들림: \"" + heard + "\"");
            final String q = rest.trim();
            main.post(() -> core.wake(q));
            main.postDelayed(this::listen, 1500);
            return true;
        }
        return false;
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle b) { handle(b, false); }
        @Override public void onEvent(int t, Bundle b) { }
        @Override public void onResults(Bundle b) {
            if (!handle(b, true)) main.postDelayed(WakeWord.this::listen, 150);
        }
        @Override public void onError(int e) {
            // 오프라인 한국어 음성 데이터가 없으면 온라인 인식으로
            if (preferOffline && (e == 12 || e == 13 || e == SpeechRecognizer.ERROR_SERVER || e == SpeechRecognizer.ERROR_NETWORK)) {
                preferOffline = false;
                core.log("오프라인 음성 인식이 안 돼서 온라인 인식으로 바꿀게요 (오류 " + e + ")");
            }
            int delay = (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) ? 1500 : 250;
            if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && sr != null) { try { sr.destroy(); } catch (Exception ignored) { } sr = null; }
            main.postDelayed(WakeWord.this::listen, delay);
        }
    };
}
