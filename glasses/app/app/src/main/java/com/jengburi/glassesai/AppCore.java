package com.jengburi.glassesai;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 앱 전체가 함께 쓰는 핵심 동작 (화면과 백그라운드 서비스가 공유). */
public class AppCore {
    public interface Ui {
        void onLog(String s);
        void onAnswer(String s);
        void onPhoto(byte[] jpeg);
        void onBusy(boolean b);
    }

    static final String COMMON_TAIL = "\n귀로 듣는 답이니 기호, 표, 마크다운 없이 말하듯이 짧게 한국어로 써 줘.";
    public static final String[] MODE_NAMES = {"자동", "번역", "문제 풀이", "설명", "쟁부리우드 제품", "직접 입력"};
    static final String[] MODE_PROMPTS = {
            "이 사진은 내가 쓴 안경 카메라로 방금 찍은 거야.\n"
                    + "- 메뉴판, 표지판 같은 글자가 있으면 한국어로 번역해서 읽어 줘.\n"
                    + "- 시험 문제가 있으면 정답과 짧은 풀이를 알려 줘.\n"
                    + "- 그 외에는 앞에 보이는 것을 2~3문장으로 설명해 줘.",
            "사진 속 글자를 모두 찾아서 한국어로 번역해 읽어 줘. 메뉴판이면 메뉴 이름과 가격을 차례로 알려 줘.",
            "사진 속 문제를 풀어 줘. 문제 번호마다 정답 번호와 한두 문장 풀이를 알려 줘.",
            "사진에 보이는 것을 앞이 잘 안 보이는 사람에게 설명하듯 3~4문장으로 자세히 설명해 줘.",
            "나는 원목 맞춤 제작 공방 '쟁부리우드'를 운영해. 사진 속 물건이나 공간을 보고, 어떤 원목 제품(포토존, 가구, 소품)을 만들어 주면 좋을지 아이디어 2~3개와 대략의 크기를 말해 줘.",
            ""
    };

    private static AppCore inst;
    public static synchronized AppCore get(Context c) {
        if (inst == null) inst = new AppCore(c.getApplicationContext());
        return inst;
    }

    public final Context ctx;
    public final SharedPreferences prefs;
    public final GlassesBle ble;
    public final GlassesWifi wifi;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    public volatile boolean busy;
    public volatile byte[] lastJpeg;
    public volatile String lastAnswer = "";
    public volatile Ui ui;
    private final StringBuilder logBuf = new StringBuilder();
    private long lastTrigger;

    private AppCore(Context ctx) {
        this.ctx = ctx;
        prefs = ctx.getSharedPreferences("glassesai", Context.MODE_PRIVATE);
        ble = new GlassesBle(ctx, this::log);
        wifi = new GlassesWifi(ctx, this::log);
        tts = new TextToSpeech(ctx, s -> {
            if (s == TextToSpeech.SUCCESS) { tts.setLanguage(Locale.KOREAN); ttsReady = true; }
        });
        ble.listener = this::onPacket;
    }

    // ---------- 기록 / 말하기 ----------
    public synchronized void log(String s) {
        logBuf.insert(0, s + "\n");
        if (logBuf.length() > 6000) logBuf.setLength(6000);
        Ui u = ui; if (u != null) u.onLog(s);
    }
    public synchronized String logText() { return logBuf.toString(); }

    public void say(String s) { if (ttsReady) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "a"); }
    public void stopSpeaking() { if (tts != null) tts.stop(); }

    /** 다 읽을 때까지 기다림 (최대 sec초). */
    public void sayAndWait(String s, int sec) {
        if (!ttsReady) return;
        CountDownLatch l = new CountDownLatch(1);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { l.countDown(); }
            @Override public void onError(String id) { l.countDown(); }
        });
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "w");
        try { l.await(sec, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    // ---------- 설정 ----------
    public int mode() { return prefs.getInt("mode", 0); }
    public String prompt() {
        int m = mode();
        String p = (m == MODE_PROMPTS.length - 1) ? prefs.getString("customPrompt", MODE_PROMPTS[0]) : MODE_PROMPTS[m];
        return p + COMMON_TAIL;
    }
    public String key() { return prefs.getString("key", "").trim(); }
    public String models() { return prefs.getString("model", "gemini-3.8-flash"); }
    public boolean voiceQuestion() { return prefs.getBoolean("voice", false); }
    public String triggerCodes() { return prefs.getString("triggers", "97"); }

    // ---------- 안경 버튼 ----------
    private void onPacket(byte[] p) {
        if (p.length < 6 || (p[0] & 0xFF) != 0xAC) return;
        int cmd = p[4] & 0xFF;
        // 자주 오는 상태 알림은 숨김
        if (cmd == 0x46 || cmd == 0x17 || cmd == 0x53 || cmd == 0x45) return;
        if (!busy) log("안경 신호: " + String.format("%02X", cmd) + " (" + GlassesBle.hex(p) + ")");
        for (String t : triggerCodes().split("[,\\s]+")) {
            try {
                if (!t.isEmpty() && Integer.parseInt(t, 16) == cmd) { trigger("안경 버튼"); return; }
            } catch (NumberFormatException ignored) { }
        }
    }

    /** 버튼/화면 어디서든 호출: 찍고 → 받고 → 묻고 → 읽기. */
    public synchronized void trigger(String source) {
        long now = System.currentTimeMillis();
        if (busy || now - lastTrigger < 3000) return;
        lastTrigger = now;
        if (key().isEmpty()) { say("먼저 앱에 제미나이 API 키를 넣어 주세요"); return; }
        setBusy(true);
        log("▶ 시작 (" + source + ")");
        new Thread(this::runFlow).start();
    }

    public void askAgain() {
        byte[] jpeg = lastJpeg;
        if (busy) return;
        if (jpeg == null) { answer("아직 찍은 사진이 없어요. 먼저 찍어 주세요."); return; }
        setBusy(true);
        new Thread(() -> {
            try { askAndSpeak(jpeg); }
            catch (Exception e) { fail(e); }
            finally { setBusy(false); }
        }).start();
    }

    private void setBusy(boolean b) { busy = b; Ui u = ui; if (u != null) u.onBusy(b); }
    private void answer(String a) { lastAnswer = a; Ui u = ui; if (u != null) u.onAnswer(a); }
    private void fail(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        log("오류: " + m);
        answer("오류: " + m);
        say("오류가 났어요. " + m);
    }

    private boolean locationOn() {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        return lm != null && lm.isLocationEnabled();
    }

    private void runFlow() {
        try {
            answer("진행 중…");
            ble.connect();
            say("찍을게요");
            ble.takePhoto();
            Thread.sleep(1500);
            if (!locationOn()) throw new Exception("휴대폰 위치(GPS)를 켜 주세요. 꺼져 있으면 안경 와이파이를 못 찾아요");
            String ssid = ble.wifiOn();
            byte[] jpeg;
            try {
                log("안경 와이파이가 켜질 때까지 5초 기다리는 중…");
                Thread.sleep(5000);
                try {
                    wifi.connect(ssid);
                } catch (Exception first) {
                    log("1차 연결 실패: " + first.getMessage() + " → 다시 시도");
                    wifi.disconnect();
                    ssid = ble.wifiOn();
                    Thread.sleep(6000);
                    wifi.connect(ssid);
                }
                String name = wifi.latestPhoto();
                if (name == null) throw new Exception("안경에서 사진을 찾지 못했어요");
                jpeg = wifi.download(name);
            } finally {
                wifi.disconnect();
                ble.transferEnd();
            }
            lastJpeg = jpeg;
            Ui u = ui; if (u != null) u.onPhoto(jpeg);
            log("인터넷 연결 기다리는 중…");
            if (!wifi.waitForInternet(25)) log("인터넷 확인이 안 됐지만 시도해 볼게요");
            askAndSpeak(jpeg);
        } catch (Exception e) {
            fail(e);
        } finally {
            setBusy(false);
        }
    }

    private void askAndSpeak(byte[] jpeg) throws Exception {
        String prompt = prompt();
        if (voiceQuestion()) {
            sayAndWait("무엇이 궁금하세요?", 5);
            String q = VoiceInput.listen(ctx, 12, this::log);
            if (q != null && !q.trim().isEmpty())
                prompt = prompt + "\n\n사용자가 이 사진에 대해 이렇게 물었어. 이 질문에 먼저 답해 줘: " + q;
        }
        say("분석 중이에요");
        log("Gemini에게 물어보는 중… (모드: " + MODE_NAMES[mode()] + ")");
        Gemini.lastFallbackModel = null;
        String a = Gemini.askWithRetry(key(), models(), prompt, jpeg, this::log);
        String fb = Gemini.lastFallbackModel;
        if (fb != null && !models().contains(fb)) prefs.edit().putString("model", fb + ", " + models()).apply();
        answer(a);
        log("답변 받음");
        say(a);
    }
}
