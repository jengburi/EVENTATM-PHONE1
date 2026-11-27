package com.jengburi.glassesai;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.os.Looper;
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
    public static final String[] MODE_NAMES = {"자동", "번역", "문제 풀이", "설명", "쟁부리우드 제품", "칼로리 경찰", "냉장고 셰프", "가격 감별사", "동물 속마음", "코디 평가", "AI 현미경", "직접 입력"};
    public static final int M_KCAL = 5, M_CHEF = 6, M_PRICE = 7, M_PET = 8, M_STYLE = 9, M_MICRO = 10;
    static final String[] MODE_PROMPTS = {
            "이 사진은 내가 쓴 안경 카메라로 방금 찍은 거야.\n"
                    + "- 메뉴판, 표지판 같은 글자가 있으면 한국어로 번역해서 읽어 줘.\n"
                    + "- 시험 문제가 있으면 정답과 짧은 풀이를 알려 줘.\n"
                    + "- 그 외에는 앞에 보이는 것을 2~3문장으로 설명해 줘.",
            "사진 속 글자를 모두 찾아서 한국어로 번역해 읽어 줘. 메뉴판이면 메뉴 이름과 가격을 차례로 알려 줘.",
            "사진 속 문제를 풀어 줘. 문제 번호마다 정답 번호와 한두 문장 풀이를 알려 줘.",
            "사진에 보이는 것을 앞이 잘 안 보이는 사람에게 설명하듯 3~4문장으로 자세히 설명해 줘.",
            "나는 원목 맞춤 제작 공방 '쟁부리우드'를 운영해. 사진 속 물건이나 공간을 보고, 어떤 원목 제품(포토존, 가구, 소품)을 만들어 주면 좋을지 아이디어 2~3개와 대략의 크기를 말해 줘.",
            "사진 속 음식을 보고 음식 이름과 대략의 칼로리를 알려 줘. 오늘 먹은 양을 생각해서 친구처럼 짧게 칭찬이나 잔소리 한마디 해 줘. "
                    + "맨 마지막 줄에 이번 음식의 칼로리 숫자만 [칼로리:650] 형식으로 적어 줘. 음식이 아니면 [칼로리:0].",
            "사진은 냉장고 안이나 식재료야. 보이는 재료를 짧게 말하고, 그 재료로 만들 수 있는 요리 1~2개를 추천해 줘. "
                    + "첫 번째 요리는 필요한 재료와 만드는 순서를 3~5단계로 말하듯이 알려 줘.",
            "사진 속 물건이 뭔지 알아보고 한국에서 보통 얼마쯤 하는지(인터넷 최저가 기준 대략) 알려 줘. "
                    + "가격표가 보이면 그 가격이 싼 편인지 비싼 편인지 판단해 줘. 정확하지 않을 수 있다는 말은 짧게만.",
            "사진 속 동물(동물이 없으면 사물)의 지금 속마음을 1인칭으로 웃기게 2~3문장 말해 줘. 표정과 자세를 근거로, 예능 자막처럼.",
            "사진 속 사람(거울 셀카 포함)의 옷차림을 패션 코치처럼 100점 만점으로 점수 매기고, 잘한 점 하나, 아쉬운 점 하나, 팁 하나를 말해 줘. 기분 나쁘지 않게 유쾌하게.",
            "이 사진은 내시경·접사 카메라로 아주 가까이에서 찍은 확대 사진이야. 무엇인지 알아맞혀 줘. "
                    + "나무면 수종(예: 월넛, 오크, 편백)과 근거(결, 색, 기공), 작은 글씨면 그대로 읽어 주고, 부품이면 이름과 규격, 식물이면 상태를 말해 줘. 2~4문장.",
            ""
    };
    /** 버튼에 붙일 수 있는 특수 동작 */
    public static final int ACT_REPLAY = -2, ACT_VOICE_ONLY = -3, ACT_CURRENT = -1, ACT_SMART = -4, ACT_PHOTO = -5, ACT_INTERPRET = -6, ACT_LISTEN = -7;

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
        interp = new Interpreter(this);
        ble.preferredAddress = prefs.getString("glassesAddr", "");
        wakeWord = new WakeWord(this);
        features = new Features(this);
        // 예전 버전의 '직접 입력'(5번) 선택 유지
        int mv = prefs.getInt("modeVer", 1), cm = prefs.getInt("mode", 0);
        if ((mv < 2 && cm == 5) || (mv == 2 && cm == 10)) prefs.edit().putInt("mode", MODE_NAMES.length - 1).apply();
        if (mv < 3) prefs.edit().putInt("modeVer", 3).apply();
    }

    public final Features features;

    public final WakeWord wakeWord;
    /** 지금 마이크 소리 크기 (화면 표시용) */
    public volatile double micRms = -1;

    /** 호출어가 들렸을 때: 뒤에 질문이 있으면 바로 묻고, 없으면 '삐' 후 듣기 */
    public void wake(String question) {
        if (busy) return;
        if (question == null || question.trim().length() < 2) smartAsk("호출어");
        else smartAsk("호출어", question);
    }

    // ---------- 드라이브 업로드 (블로그/쇼츠용) ----------
    private final java.util.concurrent.ExecutorService uploadPool = java.util.concurrent.Executors.newSingleThreadExecutor();
    public boolean uploadReady() {
        return prefs.getBoolean("autoUpload", true) && !prefs.getString("uploadUrl", "").trim().isEmpty();
    }

    /** 찍은 사진 + AI 설명을 드라이브에 올림 (뒤에서 차례로). */
    public void uploadMoment(byte[] jpeg, String mode, String q, String a) {
        if (!uploadReady()) return;
        String stamp = new java.text.SimpleDateFormat("HHmmss", Locale.KOREA).format(new java.util.Date());
        uploadPool.submit(() -> {
            try {
                wifi.waitForInternet(30);
                String url = prefs.getString("uploadUrl", "").trim(), key = prefs.getString("uploadKey", "").trim();
                if (jpeg != null) Uploader.upload(url, key, "glasses_" + stamp + ".jpg", "image/jpeg", jpeg, null);
                if (a != null && !a.isEmpty()) {
                    String note = "시각: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(new java.util.Date())
                            + "\n모드: " + mode + (q != null ? "\n질문: " + q : "") + "\nAI 설명: " + a + "\n";
                    Uploader.upload(url, key, "glasses_" + stamp + ".txt", null, null, note);
                }
                log("드라이브에 올림 (안경 촬영/오늘 날짜)");
            } catch (Exception e) { log("드라이브 업로드 실패: " + e.getMessage()); }
        });
    }

    /** 안경 속 사진·동영상을 모두 가져와 드라이브에 올림 (이미 올린 건 건너뜀). */
    public synchronized void syncAllToDrive() {
        if (busy) return;
        if (!uploadReady()) { say("먼저 드라이브 업로드 주소를 설정해 주세요"); return; }
        setBusy(true);
        new Thread(() -> {
            java.util.List<java.io.File> got = new java.util.ArrayList<>();
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.Set<String> done = new java.util.HashSet<>(prefs.getStringSet("uploadedNames", new java.util.HashSet<>()));
            try {
                answer("안경 속 파일 가져오는 중…");
                ble.connect();
                String ssid = ble.wifiOn();
                try {
                    Thread.sleep(1500);
                    wifi.connect(ssid);
                    java.util.List<String[]> files = new java.util.ArrayList<>();
                    if (ble.jianyun) { for (String n : wifi.listFtp()) files.add(new String[]{n, "0", ""}); }
                    else files = wifi.listAll();
                    for (String[] f : files) {
                        String name = f[0];
                        long size = 0; try { size = Long.parseLong(f[1]); } catch (Exception ignored) { }
                        if (done.contains(name)) continue;
                        // 크기 단위가 KB 로 오는 경우가 많음 → 30MB 넘는 건 건너뜀
                        if (size > 30 * 1024) { log("너무 커서 건너뜀: " + name); continue; }
                        java.io.File dst = new java.io.File(ctx.getCacheDir(), name.replace('/', '_'));
                        log("가져오는 중: " + name);
                        if (ble.jianyun) wifi.downloadFtpTo(name, dst); else wifi.downloadTo(name, dst);
                        if (dst.length() > 30L * 1024 * 1024) { dst.delete(); log("너무 커서 건너뜀: " + name); continue; }
                        got.add(dst); names.add(name);
                    }
                } finally {
                    wifi.disconnect();
                    ble.transferEnd();
                }
                if (got.isEmpty()) { answer("새로 올릴 파일이 없어요"); say("새로 올릴 파일이 없어요"); return; }
                wifi.waitForInternet(30);
                String url = prefs.getString("uploadUrl", "").trim(), key = prefs.getString("uploadKey", "").trim();
                int ok = 0;
                for (int i = 0; i < got.size(); i++) {
                    java.io.File f = got.get(i);
                    String n = f.getName().toLowerCase();
                    String mime = (n.endsWith(".jpg") || n.endsWith(".jpeg")) ? "image/jpeg" : n.endsWith(".mp4") ? "video/mp4"
                            : n.endsWith(".wav") ? "audio/wav" : (n.endsWith(".aac") || n.endsWith(".m4a")) ? "audio/aac" : "application/octet-stream";
                    try {
                        byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                        Uploader.upload(url, key, f.getName(), mime, b, null);
                        done.add(names.get(i)); ok++;
                        log("드라이브에 올림: " + f.getName());
                    } catch (Exception e) { log("올리기 실패 " + f.getName() + ": " + e.getMessage()); }
                    f.delete();
                }
                prefs.edit().putStringSet("uploadedNames", done).apply();
                answer(ok + "개 파일을 드라이브 '안경 촬영' 폴더에 올렸어요");
                say(ok + "개를 드라이브에 올렸어요");
            } catch (Exception e) { fail(e); }
            finally { for (java.io.File f : got) f.delete(); setBusy(false); }
        }).start();
    }

    /** 사용할 안경 바꾸기 (빈 문자열 = 자동) */
    public void selectGlasses(String addr, String name) {
        prefs.edit().putString("glassesAddr", addr).putString("glassesName", name).apply();
        ble.preferredAddress = addr;
        ble.close();
        log("사용할 안경: " + (addr.isEmpty() ? "자동 (S1Pro / M88)" : name));
    }

    public final Interpreter interp;

    // ---------- 언어별 말하기 (통역용) ----------
    public void speakQueued(String s, Locale loc) {
        if (!ttsReady) return;
        applyRate();
        tts.setLanguage(loc);
        tts.speak(s, TextToSpeech.QUEUE_ADD, null, "q" + System.nanoTime());
    }
    /** 번역이 밀릴 때는 더 빠르게 읽기 */
    public void speakQueuedFast(String s, Locale loc, boolean fast) {
        if (!ttsReady) return;
        float base = prefs.getFloat("rate", 1.0f);
        tts.setSpeechRate(fast ? Math.max(base * 1.35f, 1.4f) : base);
        tts.setLanguage(loc);
        tts.speak(s, TextToSpeech.QUEUE_ADD, null, "q" + System.nanoTime());
    }
    public boolean isSpeaking() { return ttsReady && tts.isSpeaking(); }
    public void resetLanguage() { if (ttsReady) tts.setLanguage(Locale.KOREAN); }

    /** 이 언어로 말하고 끝날 때까지 기다림. phoneSpeaker=true 면 휴대폰 스피커로(상대방이 듣게). */
    public void speakAndWait(String s, Locale loc, boolean phoneSpeaker) {
        if (!ttsReady) return;
        applyRate();
        tts.setLanguage(loc);
        if (!phoneSpeaker) { sayAndWaitRaw(s, 120); resetLanguage(); return; }
        try {
            java.io.File f = new java.io.File(ctx.getCacheDir(), "tts.wav");
            CountDownLatch l = new CountDownLatch(1);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { l.countDown(); }
                @Override public void onError(String id) { l.countDown(); }
            });
            tts.synthesizeToFile(s, null, f, "f" + System.nanoTime());
            l.await(30, TimeUnit.SECONDS);
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build());
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS))
                if (d.getType() == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { mp.setPreferredDevice(d); break; }
            CountDownLatch end = new CountDownLatch(1);
            mp.setOnCompletionListener(m -> end.countDown());
            speaking = true;
            mp.start();
            end.await(60, TimeUnit.SECONDS);
            speaking = false;
            mp.release();
        } catch (Exception e) { log("스피커 재생 실패: " + e.getMessage()); }
        resetLanguage();
    }

    // ---------- 기록 / 말하기 ----------
    public synchronized void log(String s) {
        logBuf.insert(0, s + "\n");
        if (logBuf.length() > 3000) logBuf.setLength(3000);
        Ui u = ui; if (u != null) u.onLog(s);
    }
    public synchronized String logText() { return logBuf.toString(); }

    private void applyRate() { if (ttsReady) tts.setSpeechRate(prefs.getFloat("rate", 1.0f)); }
    public void say(String s) { if (ttsReady) { applyRate(); resetLanguage(); tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "a"); } }
    public void stopSpeaking() { if (tts != null) tts.stop(); speaking = false; }

    /** 다 읽을 때까지 기다림 (최대 sec초). 중간에 멈추면 false. */
    public boolean sayAndWait(String s, int sec) {
        if (!ttsReady) return false;
        resetLanguage();
        return sayAndWaitRaw(s, sec);
    }

    boolean sayAndWaitRaw(String s, int sec) {
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
        if (m == M_KCAL) p += "\n(참고: " + features.kcalContext() + ")";
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
    /** 버튼·화면 카드로 바로 실행하는 재미 기능 */
    public static final String[] FEATURE_KEYS = {"주차", "내차", "위치", "일기", "팩트", "회의", "길안내", "영상", "사진만"};

    static int parseAction(String s) {
        s = s.trim();
        if (s.isEmpty()) return ACT_CURRENT;
        for (int i = 0; i < FEATURE_KEYS.length; i++) if (s.contains(FEATURE_KEYS[i])) return -100 - i;
        if (s.contains("다시")) return ACT_REPLAY;
        if (s.contains("듣기") || s.contains("뉴스")) return ACT_LISTEN;
        if (s.contains("통역")) return ACT_INTERPRET;
        if (s.contains("비서") || s.contains("그냥") || s.contains("물어")) return ACT_SMART;
        if (s.contains("사진") || s.contains("찍")) return ACT_PHOTO;
        if (s.contains("말로")) return ACT_VOICE_ONLY;
        for (int i = 0; i < MODE_NAMES.length; i++) if (MODE_NAMES[i].replace(" ", "").equals(s.replace(" ", ""))) return i;
        try { int n = Integer.parseInt(s); if (n >= 0 && n < MODE_NAMES.length) return n; } catch (NumberFormatException ignored) { }
        return ACT_CURRENT;
    }

    // ---------- 안경 버튼 ----------
    private void onPacket(byte[] p) {
        if (ble.jianyun) {
            if (GlassesBle.isJyAiButton(p)) {
                log("M88 AI 버튼");
                new Thread(ble::jyEndAiSession).start();
                action("안경 AI 버튼", parseAction(prefs.getString("actM88", "")));
            }
            return;
        }
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
        if (features.meetingOn) { features.stopMeeting(); return; }
        if (interp.running) { interp.stop(); return; }
        if (act <= -100) { feature(FEATURE_KEYS[-100 - act]); return; }
        if (act == ACT_INTERPRET) { interp.start(false); return; }
        if (act == ACT_LISTEN) { interp.start(true); return; }
        if ((speaking || isSpeaking()) && act != ACT_REPLAY) { stopSpeaking(); log("읽기 멈춤"); return; }
        if (act == ACT_REPLAY) { replay(); return; }
        if (act == ACT_VOICE_ONLY || act == ACT_SMART) { smartAsk(source); return; }
        if (act == ACT_CURRENT && prefs.getBoolean("buttonSmart", true)) { smartAsk(source); return; }
        trigger(source, act == ACT_PHOTO ? ACT_CURRENT : act);
    }

    public void trigger(String source) { trigger(source, ACT_CURRENT); }

    /** 기능 이름으로 실행 (화면 카드 · 안경 버튼 공용) */
    public void feature(String k) {
        switch (k) {
            case "주차": runTask("주차 기억", features::saveParking, false); break;
            case "내차": runTask("내 차 찾기", features::findCar, true); break;
            case "위치": runTask("내 위치", features::whereAmI, true); break;
            case "일기": runTask("오늘 일기", features::diary, false); break;
            case "팩트": runTask("팩트 체크", features::factCheck, true); break;
            case "회의": if (features.meetingOn) features.stopMeeting(); else features.startMeeting(); break;
            case "길안내": runTask("길안내", this::navAsk, false); break;
            case "영상": toggleVideo(); break;
            case "사진만": runTask("사진 찍기", features::photoOnly, false); break;
        }
    }

    /** 영상 녹화 시작/끝 (끝나면 휴대폰 갤러리로 가져오기) */
    public void toggleVideo() {
        if (!ble.recording) {
            if (busy) { log("다른 작업 중이에요"); return; }
            new Thread(() -> {
                try {
                    ble.connect();
                    ble.startVideo();
                    log("🎥 영상 녹화 시작");
                    say("녹화를 시작할게요. 끝낼 때 다시 누르세요");
                    Ui u = ui; if (u != null) u.onBusy(busy);
                } catch (Exception e) { log("영상 시작 실패: " + e.getMessage()); say(e.getMessage()); }
            }).start();
        } else {
            new Thread(() -> {
                try { ble.stopVideo(); log("■ 영상 녹화 끝"); } catch (Exception e) { log("영상 끝내기 실패: " + e.getMessage()); }
                Ui u = ui; if (u != null) u.onBusy(busy);
                if (prefs.getBoolean("videoFetch", true)) {
                    try { Thread.sleep(2500); } catch (InterruptedException ignored) { } // 안경이 파일을 저장할 시간
                    runTask("영상 가져오기", features::fetchVideo, false);
                } else say("녹화를 끝냈어요. 영상은 안경에 저장돼 있어요");
            }).start();
        }
    }

    /** 목적지를 말로 듣고 지도 열기 */
    private String navAsk() throws Exception {
        sayAndWait("어디로 갈까요?", 4);
        byte[] wav = AudioQ.record(ctx, glassesMic(), 8, 12, this::log);
        if (wav == null) return "못 들었어요.";
        String r = askModel("첨부 오디오에서 사용자가 가고 싶은 목적지와 이동 방법을 알아내서 [길안내:목적지:방법] 형식 한 줄로만 답해. "
                + "방법은 도보, 자동차, 대중교통 중 하나(모르면 도보). 근처 장소 찾기면 [주변:검색어].", null, wav);
        String sp = specialTag(r);
        return sp != null ? sp : "목적지를 알아듣지 못했어요.";
    }

    /** 오래 걸리는 기능을 뒤에서 실행하고 결과를 읽어 줌 */
    public void runTask(String name, java.util.concurrent.Callable<String> job, boolean save) {
        if (busy) { log("다른 작업 중이에요"); return; }
        if (key().isEmpty() && !name.contains("위치") && !name.contains("내 차")) { say("먼저 앱에 제미나이 API 키를 넣어 주세요"); return; }
        setBusy(true);
        log("▶ " + name);
        new Thread(() -> {
            try {
                answer(name + " 중…");
                String r = job.call();
                if (r != null && !r.isEmpty()) {
                    answer(r);
                    speakQueued(r, Locale.KOREAN);
                    if (save) History.save(ctx, null, name, null, r, false);
                    Ui u = ui; if (u != null) u.onHistoryChanged();
                    waitSpeechDoneQuiet(120);
                }
            } catch (Exception e) { fail(e); }
            finally { setBusy(false); }
        }).start();
    }

    private void waitSpeechDoneQuiet(int sec) {
        long end = System.currentTimeMillis() + sec * 1000L;
        try { Thread.sleep(400); } catch (InterruptedException ignored) { }
        while (System.currentTimeMillis() < end && isSpeaking()) { try { Thread.sleep(200); } catch (InterruptedException ignored) { } }
    }

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
            + "사용자가 외국인과의 대화를 통역해 달라고 하면(예: 영어 통역해 줘, 이 사람 말 통역해 줘) 다른 말 없이 [통역:언어] 라고만 답해. 언어를 모르면 [통역:자동].\n"
            + "사용자가 뉴스, 방송, 강의, 영상처럼 계속 나오는 말을 통역해 달라고 하거나 '지금 뉴스야' 같이 말하면 다른 말 없이 [듣기통역] 이라고만 답해. 요약해 달라고 하면 [듣기통역:요약].\n"
            + "사진이 필요한데 특별한 종류면 [사진] 대신: 음식 칼로리는 [사진:칼로리], 냉장고·재료로 요리 추천은 [사진:셰프], 물건 가격이 싼지 비싼지는 [사진:가격], 동물 속마음은 [사진:속마음], 옷차림 평가는 [사진:코디], 확대해서 자세히 보기(나무 종류, 작은 글씨, 부품)는 [사진:현미경].\n"
            + "길안내를 원하면(예: 서울역 가는 길 알려 줘) [길안내:목적지:도보] 처럼 답해. 방법은 도보, 자동차, 대중교통 중 하나(운전·차 얘기면 자동차, 모르면 도보).\n"
            + "지금 내 위치를 물으면 [위치], 근처 장소를 찾으면(예: 근처 편의점 어디야) [주변:편의점].\n"
            + "주차했다고 하거나 주차 위치 기억해 달라고 하면 [주차], 내 차 어디 있냐고 하면 [내차].\n"
            + "영상(동영상) 찍어 달라거나 녹화 시작이면 [영상시작], 녹화 그만·영상 끝이면 [영상끝]. 설명 없이 사진만 찍어 두라고 하면 [사진저장].\n"
            + "오늘 일기를 써 달라고 하면 [일기]. TV나 들리는 말이 사실인지 확인해 달라고 하면 [팩트체크]. 회의 기록이나 회의 녹음을 시작해 달라고 하면 [회의].\n"
            + "위 대괄호 신호로 답할 때는 다른 말 없이 신호만 써.\n"
            + "그 외에는 질문에 바로 답해. 질문을 다시 적지 마.";

    /** 그냥 물어보기: 말로 묻고, 앞을 봐야 하는 질문이면 자동으로 찍어서 대답. 이어서 대화 가능. */
    public synchronized void smartAsk(String source) { smartAsk(source, null); }

    /** firstText: 호출어와 함께 이미 말한 질문(글자). 있으면 첫 질문은 녹음 없이 이걸로. */
    public synchronized void smartAsk(String source, String firstText) {
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
                    String qText = (turn == 0 && firstText != null && firstText.trim().length() >= 2) ? firstText.trim() : null;
                    byte[] wav = null;
                    if (qText == null) {
                        wav = AudioQ.record(ctx, glassesMic(), turn == 0 ? 7 : 6, 15, this::log);
                        if (wav == null) { if (turn == 0) say("못 들었어요"); return; }
                    } else log("질문: " + qText);
                    answer("생각 중…");
                    String prompt = (qText == null ? SMART_PROMPT : SMART_PROMPT.replace("사용자가 음성으로 질문했어(첨부된 오디오).", "사용자 질문: " + qText)) + COMMON_TAIL;
                    prompt += "\n(참고: 음식 기록 - " + features.kcalContext() + " 오늘 먹은 양을 물으면 이걸로 답해.)";
                    if (ble.battery >= 0) prompt += "\n(참고: 지금 안경 배터리는 " + ble.battery + "%" + (ble.charging ? ", 충전 중" : "") + ". 배터리를 물어보면 이 값으로 답해.)";
                    if (past.length() > 0) prompt += "\n\n지금까지 대화:\n" + past;
                    if (photoForChat != null) prompt += "\n\n(앞서 찍은 사진이 함께 첨부돼 있어. 사진 관련 질문이면 이 사진을 보고 답해.)";
                    byte[] newPhoto = null;
                    String a = askSpoken(prompt, photoForChat, wav);
                    if (a.contains("[끝]")) { say("알겠어요"); return; }
                    if (a.contains("[듣기통역")) {
                        prefs.edit().putBoolean("interpSummary", a.contains("요약")).apply();
                        pendingInterp = 2; return;
                    }
                    if (a.contains("[통역")) {
                        String code = Interpreter.codeFromText(a);
                        prefs.edit().putString("interpLang", code == null ? "자동" : Interpreter.nameOf(code)).apply();
                        pendingInterp = 1; return;
                    }
                    String special = specialTag(a);
                    if (special != null) {
                        if (special.equals("@meeting")) { pendingInterp = 3; return; }
                        a = special;
                        speakQueued(a, Locale.KOREAN);
                    } else if (a.contains("[사진") && !canPhoto()) {
                        a = "이 안경은 아직 사진을 휴대폰으로 가져올 수 없어서, 보는 질문에는 답하기 어려워요. 다른 건 물어보세요.";
                        speakQueued(a, Locale.KOREAN);
                    } else if (a.contains("[사진")) {
                        int pm = photoMode(a);
                        say("잠깐만요, 볼게요");
                        byte[] jpeg = capturePhoto();
                        photoForChat = jpeg;
                        newPhoto = jpeg;
                        log("인터넷 연결 기다리는 중…");
                        if (!wifi.waitForInternet(25)) log("인터넷 확인이 안 됐지만 시도해 볼게요");
                        String p2 = prompt(pm) + (qText == null
                                ? "\n\n사용자가 음성으로 질문했어(첨부된 오디오). 사진을 보고 그 질문에 먼저 답해 줘. 질문을 다시 적지 마."
                                : "\n\n사용자 질문: " + qText + "\n사진을 보고 이 질문에 먼저 답해 줘.");
                        if (past.length() > 0) p2 += "\n\n지금까지 대화:\n" + past;
                        a = askSpoken(p2, jpeg, wav).replace("[사진]", "").trim();
                        a = features.takeKcal(a);
                    }
                    answer(a);
                    History.save(ctx, newPhoto, "그냥 물어보기", qText != null ? qText : "(음성 질문)", a, toGallery());
                    Ui u = ui; if (u != null) u.onHistoryChanged();
                    past.append("사용자: ").append(qText != null ? qText : "(음성 질문)").append("\nAI: ").append(a).append("\n");
                    boolean finished = waitSpeechDone(180);
                    if (!finished || !prefs.getBoolean("smartFollow", true)) return;
                    log("더 물어보실 게 있으면 삐 소리 뒤에 말씀하세요");
                }
            } catch (Exception e) { fail(e); }
            finally {
                setBusy(false);
                int pi = pendingInterp; pendingInterp = 0;
                if (pi == 1) interp.start(false);
                else if (pi == 2) interp.start(true);
                else if (pi == 3) features.startMeeting();
                if (pendingVideo) { pendingVideo = false; toggleVideo(); }
            }
        }).start();
    }

    private volatile int pendingInterp;
    private volatile boolean pendingVideo;

    /** [사진:칼로리] 같은 신호 → 모드 번호 (없으면 현재 모드) */
    int photoMode(String a) {
        if (a.contains("[사진:칼로리")) return M_KCAL;
        if (a.contains("[사진:셰프")) return M_CHEF;
        if (a.contains("[사진:가격")) return M_PRICE;
        if (a.contains("[사진:속마음")) return M_PET;
        if (a.contains("[사진:코디")) return M_STYLE;
        if (a.contains("[사진:현미경")) return M_MICRO;
        return mode();
    }

    static String tagArg(String a, String tag, int idx) {
        int s = a.indexOf("[" + tag);
        if (s < 0) return null;
        int e = a.indexOf(']', s);
        String body = a.substring(s + 1, e < 0 ? a.length() : e);
        String[] p = body.split("[:：]");
        return idx < p.length ? p[idx].trim() : null;
    }

    /** 길안내·위치·주차·일기 같은 신호를 처리하고 읽을 말을 돌려줌 (해당 없으면 null) */
    String specialTag(String a) throws Exception {
        if (a.contains("[길안내")) return features.navigate(tagArg(a, "길안내", 1), tagArg(a, "길안내", 2));
        if (a.contains("[위치]")) return features.whereAmI();
        if (a.contains("[주변")) return features.nearby(tagArg(a, "주변", 1));
        if (a.contains("[주차]")) return features.saveParking();
        if (a.contains("[내차]")) return features.findCar();
        if (a.contains("[일기]")) { say("오늘 일기를 써 볼게요"); return features.diary(); }
        if (a.contains("[팩트체크]")) return features.factCheck();
        if (a.contains("[회의]")) return "@meeting";
        if (a.contains("[영상시작]") || a.contains("[영상끝]")) {
            boolean wantStart = a.contains("[영상시작]");
            if (wantStart != ble.recording) pendingVideo = true;
            return wantStart ? "녹화를 시작할게요" : "녹화를 끝낼게요";
        }
        if (a.contains("[사진저장]")) return features.photoOnly();
        return null;
    }

    /** 대답이 만들어지는 대로 문장 단위로 바로 읽어 주는 장치. [사진] 같은 신호는 읽지 않음. */
    class StreamSpeaker implements Gemini.TextSink {
        final StringBuilder buf = new StringBuilder();
        boolean decided, tag; int spoken;
        @Override public void onText(String d) {
            buf.append(d);
            if (!decided) {
                String t = buf.toString().trim();
                if (t.isEmpty()) return;
                if (t.startsWith("[") || t.startsWith("STOP") || t.startsWith("none")) {
                    if (t.contains("]") || t.length() > 24) { decided = true; tag = isTag(t); } else return;
                } else decided = true;
            }
            if (!tag) flush(false);
        }
        void finish() {
            if (!decided) { decided = true; tag = isTag(buf.toString().trim()); }
            if (!tag) flush(true);
        }
        void flush(boolean end) {
            String t = buf.substring(spoken);
            int idx = -1;
            for (int i = 0; i < t.length(); i++) { char c = t.charAt(i); if (c == '.' || c == '?' || c == '!' || c == '。' || c == '\n') idx = i; }
            if (end) idx = t.length() - 1;
            if (idx < 0) return;
            String sent = t.substring(0, idx + 1).trim();
            spoken += idx + 1;
            sent = sent.replaceAll("\\[[^\\]]*\\]", "").trim();
            if (sent.length() > 0) speakQueued(sent, Locale.KOREAN);
        }
    }
    static boolean isTag(String t) {
        if (t.startsWith("STOP") || t.startsWith("none")) return true;
        for (String k : new String[]{"[사진", "[끝", "[통역", "[듣기통역", "[길안내", "[위치", "[주변", "[주차", "[내차", "[일기", "[팩트", "[회의", "[영상"})
            if (t.startsWith(k)) return true;
        return false;
    }

    /** 스트리밍으로 묻고, 문장이 나오는 대로 읽기 시작. 전체 대답을 돌려줌. */
    String askSpoken(String prompt, byte[] jpeg, byte[] wav) throws Exception {
        StreamSpeaker sp = new StreamSpeaker();
        stopSpeaking();
        speaking = true;
        Gemini.SINK.set(sp);
        try { String a = askModel(prompt, jpeg, wav); sp.finish(); return a; }
        finally { Gemini.SINK.remove(); }
    }

    /** 읽기가 끝날 때까지 기다림. 중간에 멈추면 false. */
    boolean waitSpeechDone(int sec) {
        long end = System.currentTimeMillis() + sec * 1000L;
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        while (System.currentTimeMillis() < end && speaking && isSpeaking()) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
        }
        boolean ok = speaking;
        speaking = false;
        return ok;
    }

    /** conversation() 용: 모델 전환 기억 없이 스트리밍 */
    String askSpokenRaw(String prompt, byte[] jpeg, byte[] wav) throws Exception { return askSpoken(prompt, jpeg, wav); }

    String askModel(String prompt, byte[] jpeg, byte[] wav) throws Exception {
        log("Gemini에게 물어보는 중…");
        Gemini.lastFallbackModel = null;
        String a = Gemini.askWithRetry(key(), models(), prompt, jpeg, wav, this::log);
        String fb = Gemini.lastFallbackModel;
        if (fb != null) {
            // 성공한 모델을 맨 앞으로
            StringBuilder rest = new StringBuilder();
            for (String m : models().split(",")) { m = m.trim(); if (!m.isEmpty() && !m.equals(fb)) rest.append(", ").append(m); }
            prefs.edit().putString("model", fb + rest).apply();
        }
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

    private android.os.PowerManager.WakeLock wakeLock;
    void setBusy(boolean b) {
        busy = b;
        if (b && wakeWord != null && Looper.myLooper() != Looper.getMainLooper()) wakeWord.pauseNow();
        else if (b && wakeWord != null) new Thread(wakeWord::pauseNow).start();
        try {
            if (b) {
                if (wakeLock == null) {
                    android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                    wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "glassesai:work");
                }
                if (!wakeLock.isHeld()) wakeLock.acquire(5 * 60 * 1000L);
            } else if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) { }
        Ui u = ui; if (u != null) u.onBusy(b);
    }
    void answer(String a) { lastAnswer = a; Ui u = ui; if (u != null) u.onAnswer(a); }
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

    byte[] capturePhotoPublic() throws Exception { return capturePhoto(); }

    /** 사진을 어디서 찍나: 안경 / USB 카메라(내시경·웹캠) */
    public boolean usbMode() { return prefs.getString("camSource", "안경").contains("USB"); }
    public boolean canPhoto() { return usbMode() || ble.canFetchPhoto(); }

    /** 안경으로 찍고 사진을 받아 온다 (와이파이 전환 포함). */
    private byte[] capturePhoto() throws Exception {
        if (usbMode()) {
            say("찍을게요");
            byte[] jpeg = UsbCam.capture(ctx, this::log);
            log("USB 카메라 사진 " + jpeg.length / 1024 + "KB");
            lastJpeg = jpeg;
            Ui u = ui; if (u != null) u.onPhoto(jpeg);
            return jpeg;
        }
        ble.connect();
            say("찍을게요");
            ble.takePhoto();
            Thread.sleep(500);
            if (!locationOn()) throw new Exception("휴대폰 위치(GPS)를 켜 주세요. 꺼져 있으면 안경 와이파이를 못 찾아요");
            String ssid = ble.wifiOn();
            byte[] jpeg;
            try {
                Thread.sleep(1500); // 안경 와이파이가 켜질 시간 (연결 요청이 뜰 때까지 알아서 기다림)
                try {
                    wifi.connect(ssid);
                } catch (Exception first) {
                    log("1차 연결 실패: " + first.getMessage() + " → 다시 시도");
                    wifi.disconnect();
                    ssid = ble.wifiOn();
                    Thread.sleep(4000);
                    wifi.connect(ssid);
                }
                String name = ble.jianyun ? wifi.latestPhotoFtp() : wifi.latestPhoto();
                if (name == null) throw new Exception("안경에서 사진을 찾지 못했어요");
                jpeg = ble.jianyun ? wifi.downloadFtp(name) : wifi.download(name);
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
            String a = askSpokenRaw(prompt, jpeg, wav);
            if (a.contains("[끝]")) { say("알겠어요"); return; }
            a = features.takeKcal(a);
            String fb = Gemini.lastFallbackModel;
            if (fb != null && !models().contains(fb)) prefs.edit().putString("model", fb + ", " + models()).apply();
            answer(a);
            log("답변 받음");
            History.save(ctx, turn == 0 ? jpeg : null, modeName, ql, a, toGallery());
            Ui u = ui; if (u != null) u.onHistoryChanged();
            past.append(ql != null ? "사용자: " + ql + "\n" : "").append("AI: ").append(a).append("\n");

            if (!followUp()) { waitSpeechDone(180); return; }
            boolean finished = waitSpeechDone(180);
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
