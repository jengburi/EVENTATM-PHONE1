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
        void onHistoryChanged();
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
    /** 버튼에 붙일 수 있는 특수 동작 */
    public static final int ACT_REPLAY = -2, ACT_VOICE_ONLY = -3, ACT_CURRENT = -1, ACT_SMART = -4, ACT_PHOTO = -5;

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
    public volatile boolean speaking;
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

    private void applyRate() { if (ttsReady) tts.setSpeechRate(prefs.getFloat("rate", 1.0f)); }
    public void say(String s) { if (ttsReady) { applyRate(); tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "a"); } }
    public void stopSpeaking() { if (tts != null) tts.stop(); speaking = false; }

    /** 다 읽을 때까지 기다림 (최대 sec초). 중간에 멈추면 false. */
    public boolean sayAndWait(String s, int sec) {
        if (!ttsReady) return false;
        applyRate();
        CountDownLatch l = new CountDownLatch(1);
        final boolean[] ok = {true};
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { l.countDown(); }
            @Override public void onError(String id) { ok[0] = false; l.countDown(); }
            @Override public void onStop(String id, boolean interrupted) { ok[0] = false; l.countDown(); }
        });
        speaking = true;
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "w" + System.nanoTime());
        try { l.await(sec, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        boolean stopped = !speaking;
        speaking = false;
        return ok[0] && !stopped;
    }

    // ---------- 설정 ----------
    public int mode() { return prefs.getInt("mode", 0); }
    public String prompt(int m) {
        if (m < 0 || m >= MODE_PROMPTS.length) m = mode();
        String p = (m == MODE_PROMPTS.length - 1) ? prefs.getString("customPrompt", MODE_PROMPTS[0]) : MODE_PROMPTS[m];
        return p + COMMON_TAIL;
    }
    public String key() { return prefs.getString("key", "").trim(); }
    public String models() { return prefs.getString("model", "gemini-3.8-flash"); }
    public boolean voiceQuestion() { return prefs.getBoolean("voice", false); }
    public boolean followUp() { return prefs.getBoolean("followUp", false); }
    public boolean toGallery() { return prefs.getBoolean("gallery", true); }
    public String triggerCodes() { return prefs.getString("triggers", "97"); }
    /** true = Gemini 가 녹음을 직접 들음(추천), false = 휴대폰 음성 인식 */
    public boolean geminiListens() { return prefs.getBoolean("geminiListen", true); }
    public boolean glassesMic() { return prefs.getBoolean("glassesMic", true); }

    /** 말로 한 질문: 글자(text) 또는 녹음(wav) 중 하나. */
    static class Question {
        String text; byte[] wav;
        boolean empty() { return (text == null || text.trim().isEmpty()) && wav == null; }
        String label() { return text != null ? text : (wav != null ? "(음성 질문)" : null); }
    }

    Question listenQuestion(int waitSec) {
        Question q = new Question();
        if (geminiListens()) q.wav = AudioQ.record(ctx, glassesMic(), waitSec, 15, this::log);
        else q.text = VoiceInput.listen(ctx, waitSec + 6, glassesMic(), this::log);
        return q;
    }

    static final String AUDIO_HINT = "\n\n사용자가 음성으로 질문했어(첨부된 오디오). 오디오 속 질문을 알아듣고 그 질문에 먼저 답해 줘. "
            + "질문을 다시 적지 말고 바로 답해. 사용자가 '없어', '그만', '끝' 같은 뜻으로 말했다면 다른 말 없이 [끝] 이라고만 답해.";

    /** "번역", "1", "다시듣기", "말로만" 같은 이름을 동작 번호로. */
    static int parseAction(String s) {
        s = s.trim();
        if (s.isEmpty()) return ACT_CURRENT;
        if (s.contains("다시")) return ACT_REPLAY;
        if (s.contains("비서") || s.contains("그냥") || s.contains("물어")) return ACT_SMART;
        if (s.contains("사진") || s.contains("찍")) return ACT_PHOTO;
        if (s.contains("말로")) return ACT_VOICE_ONLY;
        for (int i = 0; i < MODE_NAMES.length; i++) if (MODE_NAMES[i].replace(" ", "").equals(s.replace(" ", ""))) return i;
        try { int n = Integer.parseInt(s); if (n >= 0 && n < MODE_NAMES.length) return n; } catch (NumberFormatException ignored) { }
        return ACT_CURRENT;
    }

    // ---------- 안경 버튼 ----------
    private void onPacket(byte[] p) {
        if (p.length < 6 || (p[0] & 0xFF) != 0xAC) return;
        int cmd = p[4] & 0xFF;
        if (cmd == 0x46 || cmd == 0x17 || cmd == 0x53 || cmd == 0x45) return;
        if (!busy) log("안경 신호: " + String.format("%02X", cmd) + " (" + GlassesBle.hex(p) + ")");
        // 형식: "97" 또는 "97:번역, 51:다시듣기, 52:말로만"
        for (String t : triggerCodes().split(",")) {
            String[] kv = t.trim().split("[:=]");
            if (kv[0].trim().isEmpty()) continue;
            try {
                if (Integer.parseInt(kv[0].trim(), 16) == cmd) {
                    action("안경 버튼 " + kv[0].trim(), kv.length > 1 ? parseAction(kv[1]) : ACT_CURRENT);
                    return;
                }
            } catch (NumberFormatException ignored) { }
        }
    }

    /** 버튼에 따라 알맞은 동작 실행. */
    public void action(String source, int act) {
        if (speaking && act != ACT_REPLAY) { stopSpeaking(); log("읽기 멈춤"); return; }
        if (act == ACT_REPLAY) { replay(); return; }
        if (act == ACT_VOICE_ONLY || act == ACT_SMART) { smartAsk(source); return; }
        if (act == ACT_CURRENT && prefs.getBoolean("buttonSmart", true)) { smartAsk(source); return; }
        trigger(source, act == ACT_PHOTO ? ACT_CURRENT : act);
    }

    public void trigger(String source) { trigger(source, ACT_CURRENT); }

    /** 찍고 → 받고 → 묻고 → 읽기. modeOverride 가 0 이상이면 그 모드로. */
    public synchronized void trigger(String source, int modeOverride) {
        long now = System.currentTimeMillis();
        if (busy || now - lastTrigger < 3000) return;
        lastTrigger = now;
        if (key().isEmpty()) { say("먼저 앱에 제미나이 API 키를 넣어 주세요"); return; }
        setBusy(true);
        int m = modeOverride >= 0 ? modeOverride : mode();
        log("▶ 시작 (" + source + ", " + MODE_NAMES[m] + ")");
        new Thread(() -> runFlow(m)).start();
    }

    public void replay() {
        String a = lastAnswer;
        if (a == null || a.isEmpty() || a.startsWith("진행")) { say("아직 들려드릴 답이 없어요"); return; }
        log("마지막 답 다시 읽기");
        say(a);
    }

    public void voiceOnly(String source) { smartAsk(source); }

    static final String SMART_PROMPT = "너는 사용자가 쓰고 있는 AI 안경 속 비서야. 친구처럼 짧고 자연스럽게 한국어로 답해 줘.\n"
            + "사용자가 음성으로 질문했어(첨부된 오디오).\n"
            + "만약 답하려면 사용자 눈앞을 봐야 하는 질문이면(예: 이거 뭐야, 저거 뭐야, 앞에 뭐 있어, 이거 번역해 줘, 이거 읽어 줘, 이거 얼마야, 이 문제 풀어 줘) "
            + "다른 말 없이 [사진] 이라고만 답해.\n"
            + "사용자가 '없어', '그만', '끝', '됐어' 같은 뜻으로 말했다면 다른 말 없이 [끝] 이라고만 답해.\n"
            + "그 외에는 질문에 바로 답해. 질문을 다시 적지 마.";

    /** 그냥 물어보기: 말로 묻고, 앞을 봐야 하는 질문이면 자동으로 찍어서 대답. 이어서 대화 가능. */
    public synchronized void smartAsk(String source) {
        long now = System.currentTimeMillis();
        if (busy || now - lastTrigger < 1500) return;
        lastTrigger = now;
        if (key().isEmpty()) { say("먼저 앱에 제미나이 API 키를 넣어 주세요"); return; }
        setBusy(true);
        log("▶ 그냥 물어보기 (" + source + ")");
        new Thread(() -> {
            try {
                StringBuilder past = new StringBuilder();
                byte[] photoForChat = null;
                for (int turn = 0; turn < 8; turn++) {
                    byte[] wav = AudioQ.record(ctx, glassesMic(), turn == 0 ? 7 : 6, 15, this::log);
                    if (wav == null) { if (turn == 0) say("못 들었어요"); return; }
                    answer("생각 중…");
                    String prompt = SMART_PROMPT + COMMON_TAIL;
                    if (past.length() > 0) prompt += "\n\n지금까지 대화:\n" + past;
                    if (photoForChat != null) prompt += "\n\n(앞서 찍은 사진이 함께 첨부돼 있어. 사진 관련 질문이면 이 사진을 보고 답해.)";
                    byte[] newPhoto = null;
                    String a = askModel(prompt, photoForChat, wav);
                    if (a.contains("[끝]")) { say("알겠어요"); return; }
                    if (a.contains("[사진]")) {
                        say("잠깐만요, 볼게요");
                        byte[] jpeg = capturePhoto();
                        photoForChat = jpeg;
                        newPhoto = jpeg;
                        log("인터넷 연결 기다리는 중…");
                        if (!wifi.waitForInternet(25)) log("인터넷 확인이 안 됐지만 시도해 볼게요");
                        String p2 = prompt(mode()) + "\n\n사용자가 음성으로 질문했어(첨부된 오디오). 사진을 보고 그 질문에 먼저 답해 줘. 질문을 다시 적지 마.";
                        if (past.length() > 0) p2 += "\n\n지금까지 대화:\n" + past;
                        a = askModel(p2, jpeg, wav).replace("[사진]", "").trim();
                    }
                    answer(a);
                    History.save(ctx, newPhoto, "그냥 물어보기", "(음성 질문)", a, toGallery());
                    Ui u = ui; if (u != null) u.onHistoryChanged();
                    past.append("사용자: (음성 질문)\nAI: ").append(a).append("\n");
                    boolean finished = sayAndWait(a, 180);
                    if (!finished || !prefs.getBoolean("smartFollow", true)) return;
                    log("더 물어보실 게 있으면 삐 소리 뒤에 말씀하세요");
                }
            } catch (Exception e) { fail(e); }
            finally { setBusy(false); }
        }).start();
    }

    private String askModel(String prompt, byte[] jpeg, byte[] wav) throws Exception {
        log("Gemini에게 물어보는 중…");
        Gemini.lastFallbackModel = null;
        String a = Gemini.askWithRetry(key(), models(), prompt, jpeg, wav, this::log);
        String fb = Gemini.lastFallbackModel;
        if (fb != null && !models().contains(fb)) prefs.edit().putString("model", fb + ", " + models()).apply();
        return a;
    }

    public void askAgain() {
        byte[] jpeg = lastJpeg;
        if (busy) return;
        if (jpeg == null) { answer("아직 찍은 사진이 없어요. 먼저 찍어 주세요."); return; }
        setBusy(true);
        new Thread(() -> {
            try { askAndSpeak(jpeg, mode()); }
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

    /** 안경으로 찍고 사진을 받아 온다 (와이파이 전환 포함). */
    private byte[] capturePhoto() throws Exception {
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
            return jpeg;
    }

    private void runFlow(int m) {
        try {
            answer("진행 중…");
            byte[] jpeg = capturePhoto();
            log("인터넷 연결 기다리는 중…");
            if (!wifi.waitForInternet(25)) log("인터넷 확인이 안 됐지만 시도해 볼게요");
            askAndSpeak(jpeg, m);
        } catch (Exception e) {
            fail(e);
        } finally {
            setBusy(false);
        }
    }

    private void askAndSpeak(byte[] jpeg, int m) throws Exception {
        Question q = null;
        if (voiceQuestion()) {
            sayAndWait("무엇이 궁금하세요?", 5);
            q = listenQuestion(7);
        }
        conversation(jpeg, prompt(m), MODE_NAMES[m], q);
    }

    /** 첫 질문 + (연속 대화가 켜져 있으면) 이어지는 질문들. */
    private void conversation(byte[] jpeg, String basePrompt, String modeName, Question firstQ) throws Exception {
        StringBuilder past = new StringBuilder();
        Question q = firstQ;
        for (int turn = 0; turn < 6; turn++) {
            String prompt = basePrompt;
            if (past.length() > 0) prompt += "\n\n지금까지 대화:\n" + past;
            byte[] wav = null;
            if (q != null && q.text != null && !q.text.trim().isEmpty()) prompt += "\n\n사용자 질문 (이 질문에 먼저 답해 줘): " + q.text;
            else if (q != null && q.wav != null) { prompt += AUDIO_HINT; wav = q.wav; }
            String ql = q == null ? null : q.label();
            if (turn == 0) say("분석 중이에요");
            log("Gemini에게 물어보는 중… (" + modeName + (ql != null ? ", " + ql : "") + ")");
            Gemini.lastFallbackModel = null;
            String a = Gemini.askWithRetry(key(), models(), prompt, jpeg, wav, this::log);
            if (a.contains("[끝]")) { say("알겠어요"); return; }
            String fb = Gemini.lastFallbackModel;
            if (fb != null && !models().contains(fb)) prefs.edit().putString("model", fb + ", " + models()).apply();
            answer(a);
            log("답변 받음");
            History.save(ctx, turn == 0 ? jpeg : null, modeName, ql, a, toGallery());
            Ui u = ui; if (u != null) u.onHistoryChanged();
            past.append(ql != null ? "사용자: " + ql + "\n" : "").append("AI: ").append(a).append("\n");

            if (!followUp()) { say(a); return; }
            boolean finished = sayAndWait(a, 180);
            if (!finished) return; // 읽다가 멈추면 대화 끝
            sayAndWait("더 궁금한 거 있으세요?", 5);
            q = listenQuestion(6);
            if (q.empty() || (q.text != null && (q.text.contains("없어") || q.text.contains("그만") || q.text.contains("끝")))) {
                say("알겠어요");
                return;
            }
        }
    }
}
