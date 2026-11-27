package com.jengburi.glassesai;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
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
 * 발음이 조금 달라도(쟁불이·장부리·잼부리·생부리…) 자모 단위로 비슷하면 인정.
 * 인식기 3가지(기기 내장 → 기본 오프라인 → 기본 온라인)를 차례로 시도.
 */
public class WakeWord {
    private final AppCore core;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private volatile boolean running;
    private boolean muted;
    /** 0 = 기기 내장 인식기, 1 = 기본(오프라인 우선), 2 = 기본(온라인) */
    private int mode = 0;
    private int badErrors = 0, lastErr = -1;
    private String lastLogged = "";
    /** 마지막으로 알아들은 말 (화면 표시용) */
    public volatile String lastHeard = "";

    WakeWord(AppCore core) { this.core = core; }

    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        if (!SpeechRecognizer.isRecognitionAvailable(core.ctx)) { core.log("이 휴대폰은 음성 인식을 지원하지 않아 호출어를 쓸 수 없어요"); return; }
        running = true;
        mode = onDeviceOk() ? 0 : 1;
        badErrors = 0;
        core.log("호출어 대기 시작: \"" + word() + "\" (" + modeName() + ")");
        main.post(this::listen);
    }

    public void stop() {
        running = false;
        main.post(() -> { destroy(); mute(false); });
        core.log("호출어 대기 끝");
    }

    private void destroy() {
        if (sr != null) { try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { } sr = null; }
    }

    /** 다른 작업이 마이크를 써야 할 때 즉시 듣기 멈춤 (작업이 끝나면 listen() 이 알아서 다시 시작) */
    public void pauseNow() {
        if (!running) return;
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        main.post(() -> { destroy(); mute(false); done.countDown(); });
        try { done.await(1, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        try { Thread.sleep(300); } catch (InterruptedException ignored) { } // 마이크가 완전히 풀릴 시간
    }

    String word() { return core.prefs.getString("wakeWord", "쟁부리야"); }

    private boolean onDeviceOk() {
        try { return Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(core.ctx); }
        catch (Exception e) { return false; }
    }

    private String modeName() { return mode == 0 ? "기기 내장 인식" : mode == 1 ? "기본 인식·오프라인" : "기본 인식·온라인"; }

    private void nextMode(String why) {
        if (mode >= 2) return;
        mode++;
        badErrors = 0;
        destroy();
        core.log("호출어: " + why + " → " + modeName() + "으로 바꿀게요");
    }

    // ---------------- 호출어 비교 ----------------

    static String norm(String s) {
        return s == null ? "" : s.replace(" ", "").replace(",", "").replace(".", "").replace("!", "").replace("?", "").replace("~", "");
    }

    /** 한글을 자모로 풀기 (쟁 → ㅈㅐㅇ) */
    static String jamo(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int x = c - 0xAC00;
                b.append((char) (0x1100 + x / 588)).append((char) (0x1161 + (x % 588) / 28));
                if (x % 28 > 0) b.append((char) (0x11A7 + x % 28));
            } else b.append(c);
        }
        return b.toString();
    }

    static int lev(String a, String b) {
        int[] p = new int[b.length() + 1], q = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) p[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            q[0] = i;
            for (int j = 1; j <= b.length(); j++)
                q[j] = Math.min(Math.min(q[j - 1] + 1, p[j] + 1), p[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = p; p = q; q = t;
        }
        return p[b.length()];
    }

    static String stripBase(String w) {
        return (w.length() > 1 && (w.endsWith("야") || w.endsWith("아"))) ? w.substring(0, w.length() - 1) : w;
    }

    /** 인식 결과에서 호출어를 찾으면 그 뒤의 말을 돌려줌 (없으면 null) */
    String match(String heard) {
        String h = norm(heard);
        if (h.isEmpty()) return null;
        java.util.List<String> bases = new ArrayList<>();
        String base = stripBase(norm(word()));
        if (!base.isEmpty()) bases.add(base);
        // 쟁부리 계열(쟁불이·장부리 등)로 바꿔 적었어도 기본 이름도 같이 인정
        if (lev(jamo(base), jamo("쟁부리")) <= 2 && !base.equals("쟁부리")) bases.add("쟁부리");
        int bestD = 99, bestEnd = -1;
        for (String b : bases) {
            String bj = jamo(b);
            int tol = bj.length() >= 7 ? 2 : bj.length() >= 4 ? 1 : 0;
            int L = b.length();
            for (int len = Math.max(1, L - 1); len <= L + 1; len++) {
                for (int i = 0; i + len <= h.length(); i++) {
                    int d = lev(jamo(h.substring(i, i + len)), bj);
                    if (d <= tol && (d < bestD || (d == bestD && i + len < bestEnd))) { bestD = d; bestEnd = i + len; }
                }
            }
        }
        // 기본 쟁부리 계열이면 '○부리'로 들려도 인정
        if (bestEnd < 0 && bases.contains("쟁부리") || bestEnd < 0 && base.equals("쟁부리")) {
            int i = h.indexOf("부리");
            if (i >= 1 && i <= 3) bestEnd = i + 2;
        }
        if (bestEnd < 0) return null;
        String rest = h.substring(bestEnd);
        if (rest.startsWith("야") || rest.startsWith("아")) rest = rest.substring(1);
        return rest;
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
                if (mode == 0 && Build.VERSION.SDK_INT >= 31) sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(core.ctx);
                else sr = SpeechRecognizer.createSpeechRecognizer(core.ctx);
                sr.setRecognitionListener(listener);
            }
            mute(true);
            Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
            it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            if (mode != 0) it.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, mode == 1);
            it.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000);
            sr.startListening(it);
        } catch (Exception e) {
            if (mode == 0) nextMode("기기 내장 인식 시작 실패");
            main.postDelayed(this::listen, 1500);
        }
    }

    /** 부분 결과로 들은 마지막 말 (최종 결과가 안 오는 휴대폰 대비) */
    private String lastPartial = "";
    private long fired = 0;
    private final Runnable partialTimeout = () -> {
        // 부분 결과에서 호출어가 들렸는데 0.9초 동안 말이 더 없으면 그대로 실행
        if (!lastPartial.isEmpty()) fire(lastPartial, "부분");
    };

    /** 호출어가 들어 있으면 AI 실행. 실행했으면 true. */
    private boolean fire(String heard, String how) {
        String rest = match(heard);
        if (rest == null) return false;
        if (System.currentTimeMillis() - fired < 3000) return false;
        fired = System.currentTimeMillis();
        main.removeCallbacks(partialTimeout);
        lastPartial = "";
        destroy();
        mute(false);
        core.log("✅ 호출어 들림: \"" + heard + "\"");
        if (core.busy) core.log("다른 작업 중이라 호출어를 건너뛰었어요");
        final String q = rest.trim();
        new Thread(() -> core.wake(q)).start();
        main.postDelayed(this::listen, 2000);
        return true;
    }

    private void handlePartial(Bundle b) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r == null || r.isEmpty() || r.get(0) == null || r.get(0).trim().isEmpty()) return;
        String heard = r.get(0);
        lastHeard = heard;
        main.removeCallbacks(partialTimeout);
        for (String h : r) {
            if (h != null && match(h) != null) {
                lastPartial = h;
                // 이어서 질문을 말하는 중이면 조금 더 기다림
                main.postDelayed(partialTimeout, match(h).trim().isEmpty() ? 900 : 1500);
                return;
            }
        }
        lastPartial = "";
    }

    private boolean handleFinal(Bundle b) {
        main.removeCallbacks(partialTimeout);
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r != null) for (String h : r) if (h != null && fire(h, "최종")) return true;
        if (!lastPartial.isEmpty() && fire(lastPartial, "부분")) return true;
        String first = (r != null && !r.isEmpty() && r.get(0) != null) ? r.get(0) : "";
        if (!first.trim().isEmpty()) {
            lastHeard = first;
            if (!first.equals(lastLogged)) { lastLogged = first; core.log("👂 들은 말: \"" + first + "\" (호출어 아님)"); }
        }
        lastPartial = "";
        return false;
    }

    static String errName(int e) {
        switch (e) {
            case 1: return "네트워크 시간초과";
            case 2: return "네트워크 오류";
            case 3: return "마이크 오류";
            case 4: return "서버 오류";
            case 5: return "인식기 오류";
            case 6: return "말 없음";
            case 7: return "못 알아들음";
            case 8: return "인식기 사용 중";
            case 9: return "권한 없음";
            case 10: return "요청 과다";
            case 11: return "서버 연결 끊김";
            case 12: return "한국어 미지원";
            case 13: return "한국어 데이터 없음";
            default: return "오류";
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle b) { handlePartial(b); }
        @Override public void onEvent(int t, Bundle b) { }
        @Override public void onResults(Bundle b) {
            badErrors = 0;
            if (!handleFinal(b)) main.postDelayed(WakeWord.this::listen, 100);
        }
        @Override public void onError(int e) {
            // 최종 결과 없이 끝나도 부분 결과에 호출어가 있었으면 실행
            main.removeCallbacks(partialTimeout);
            if (!lastPartial.isEmpty() && fire(lastPartial, "부분")) return;
            boolean normal = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (!normal) {
                if (e != lastErr) core.log("호출어 인식 문제: " + errName(e) + " (코드 " + e + ", " + modeName() + ")");
                lastErr = e;
                badErrors++;
                if (e == 12 || e == 13) nextMode(errName(e));
                else if (badErrors >= 4) nextMode("오류가 반복됨");
            }
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) core.log("마이크 권한을 '항상 허용'으로 바꿔 주세요");
            int delay = normal ? 100 : (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) ? 1500 : 500;
            if (!normal) destroy(); // 문제가 생기면 인식기를 새로 만들기
            main.postDelayed(WakeWord.this::listen, delay);
        }
    };
}
