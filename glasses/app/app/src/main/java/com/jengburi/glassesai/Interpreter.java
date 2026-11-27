package com.jengburi.glassesai;

import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 통역 두 가지.
 *  - 대화 통역: 한 사람 말이 끝나면 번역. 외국어→한국어(안경), 한국어→외국어(휴대폰 스피커).
 *  - 듣기 통역: 뉴스/강의처럼 계속 말하는 것을 쉬지 않고 녹음하며 몇 초씩 잘라 한국어로 이어서 번역(안경).
 */
public class Interpreter {
    public static final String[] LANG_NAMES = {"자동", "영어", "일본어", "중국어", "베트남어", "태국어", "프랑스어", "스페인어"};
    static final String[] CODES = {"auto", "en", "ja", "zh", "vi", "th", "fr", "es"};

    private final AppCore core;
    public volatile boolean running;
    public volatile boolean listenMode;
    private String lastForeign = "en";

    Interpreter(AppCore core) { this.core = core; }

    static Locale locale(String code) {
        switch (code) {
            case "en": return Locale.US;
            case "ja": return Locale.JAPANESE;
            case "zh": return Locale.CHINESE;
            case "vi": return new Locale("vi", "VN");
            case "th": return new Locale("th", "TH");
            case "fr": return Locale.FRENCH;
            case "es": return new Locale("es", "ES");
            default: return Locale.KOREAN;
        }
    }

    static String nameOf(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return LANG_NAMES[i];
        return code.equals("ko") ? "한국어" : code;
    }

    /** "영어", "일본어" 같은 말에서 언어 코드 찾기 (없으면 null). */
    static String codeFromText(String s) {
        if (s == null) return null;
        for (int i = 1; i < LANG_NAMES.length; i++) if (s.contains(LANG_NAMES[i].replace("어", ""))) return CODES[i];
        if (s.contains("en")) return "en";
        return null;
    }

    String targetCode() {
        String sel = core.prefs.getString("interpLang", "자동");
        for (int i = 1; i < LANG_NAMES.length; i++) if (LANG_NAMES[i].equals(sel)) return CODES[i];
        return lastForeign;
    }

    public synchronized void start(boolean listen) {
        if (running) return;
        if (core.key().isEmpty()) { core.say("먼저 앱에 제미나이 API 키를 넣어 주세요"); return; }
        if (core.busy) { core.log("다른 작업 중이라 통역을 시작하지 못했어요"); return; }
        running = true;
        listenMode = listen;
        core.setBusy(true);
        core.log(listen ? "▶ 듣기 통역 시작 (뉴스/강의)" : "▶ 대화 통역 시작");
        new Thread(listen ? this::listenLoop : this::convoLoop).start();
    }

    public void stop() {
        if (!running) return;
        running = false;
        AudioQ.cancel = true;
        core.stopSpeaking();
        core.log("■ 통역 끝");
        core.say("통역을 끝낼게요");
    }

    // ---------------- 대화 통역 ----------------
    private void convoLoop() {
        try {
            String tgt = targetCode();
            core.sayAndWait("통역을 시작할게요. " + (core.prefs.getString("interpLang", "자동").equals("자동")
                    ? "상대방 언어는 자동으로 알아낼게요." : nameOf(tgt) + "로 통역할게요."), 8);
            boolean glassesMic = core.prefs.getBoolean("interpGlassesMic", false);
            boolean phoneSpeaker = core.prefs.getBoolean("interpSpeaker", true);
            int idle = 0;
            while (running) {
                byte[] wav = AudioQ.record(core.ctx, glassesMic, 30, 25, core::log);
                if (!running) break;
                if (wav == null) { if (++idle >= 4) { core.say("소리가 없어서 통역을 끝낼게요"); break; } continue; }
                idle = 0;
                tgt = targetCode();
                boolean suggest = core.prefs.getBoolean("interpSuggest", false);
                String prompt = "너는 전문 통역사야. 첨부 오디오는 대화 중 한 사람의 말이야.\n"
                        + "- 한국어로 말했으면 " + nameOf(tgt) + "(" + tgt + ")로 번역해.\n"
                        + "- 한국어가 아니면 한국어로 번역해.\n"
                        + "- 한국어로 '통역 그만', '통역 끝' 같은 뜻으로 말했으면 STOP 이라고만 답해.\n"
                        + "출력은 딱 한 줄: 원래언어코드|번역언어코드|번역문\n"
                        + "언어 코드는 ko, en, ja, zh, vi, th, fr, es 중에서 골라. 말이 안 들리거나 잡음뿐이면 none 이라고만 답해.\n"
                        + "번역문은 자연스러운 말투로, 설명이나 따옴표 없이."
                        + (suggest ? "\n- 외국어를 한국어로 번역한 경우에만 맨 뒤에 |추천 대답 을 붙여: 사용자가 상대에게 바로 할 만한 아주 짧은 대답(한국어). 예: en|ko|어디서 왔어요?|한국에서 왔다고 하세요" : "");
                String r = core.askModel(prompt, null, wav).trim();
                if (r.contains("STOP")) { core.say("통역을 끝낼게요"); break; }
                if (r.startsWith("none") || !r.contains("|")) continue;
                String[] p = r.split("\\|", 4);
                if (p.length < 3 || p[2].trim().isEmpty()) continue;
                String src = p[0].trim(), dst = p[1].trim(), text = p[2].trim();
                if (!src.equals("ko") && !src.equals("none")) lastForeign = src;
                core.answer("[" + nameOf(src) + " → " + nameOf(dst) + "]\n" + text);
                History.save(core.ctx, null, "대화 통역", nameOf(src) + "→" + nameOf(dst), text, false);
                if (core.ui != null) core.ui.onHistoryChanged();
                if (dst.equals("ko")) {
                    core.speakAndWait(text, Locale.KOREAN, false);
                    if (suggest && p.length > 3 && !p[3].trim().isEmpty()) {
                        core.answer("[" + nameOf(src) + " → 한국어]\n" + text + "\n💡 " + p[3].trim());
                        core.speakAndWait("추천 대답. " + p[3].trim(), Locale.KOREAN, false);
                    }
                }
                else core.speakAndWait(text, locale(dst), phoneSpeaker);
            }
        } catch (Exception e) {
            core.log("통역 오류: " + e.getMessage());
            core.say("통역 중 오류가 났어요");
        } finally {
            running = false;
            core.resetLanguage();
            core.setBusy(false);
        }
    }

    // ---------------- 듣기 통역 (뉴스/강의) ----------------
    private void listenLoop() {
        boolean summary = core.prefs.getBoolean("interpSummary", false);
        boolean glassesMic = core.prefs.getBoolean("interpGlassesMic", false);
        String saved = "";
        try {
            core.sayAndWait(summary ? "요약 통역을 시작할게요" : "듣기 통역을 시작할게요", 6);
            if (core.prefs.getBoolean("liveMode", true)) {
                LiveTranslator lt = new LiveTranslator(core);
                if (lt.run(() -> running, summary, glassesMic)) { saved = lt.all.toString().trim(); return; }
                if (!running) return;
            }
            saved = chunkLoop(summary, glassesMic);
        } catch (Exception e) {
            core.log("듣기 통역 오류: " + e.getMessage());
        } finally {
            running = false;
            if (!saved.isEmpty()) History.save(core.ctx, null, "듣기 통역", null, saved, false);
            if (core.ui != null) core.ui.onHistoryChanged();
            core.setBusy(false);
        }
    }

    /** 기존 방식: 짧은 조각으로 잘라 최대 3개를 동시에 번역하고, 순서대로 읽기. */
    private String chunkLoop(boolean summary, boolean glassesMic) throws Exception {
        final long[] lastVoice = {System.currentTimeMillis()};
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(core.prefs.getBoolean("paidKey", false) ? 3 : 2);
        final java.util.concurrent.ConcurrentHashMap<Integer, String> results = new java.util.concurrent.ConcurrentHashMap<>();
        final int[] nextIn = {0};
        final StringBuilder prev = new StringBuilder();
        final long[] coolUntil = {0};
        final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
        Thread rec = new Thread(() -> AudioQ.stream(core.ctx, glassesMic, () -> running, wav0 -> {
            lastVoice[0] = System.currentTimeMillis();
            byte[] wav;
            synchronized (pending) {
                // 한도에 걸려 쉬는 동안엔 모아 두기
                if (System.currentTimeMillis() < coolUntil[0]) { pending.write(wav0, 44, wav0.length - 44); return; }
                if (pending.size() > 0) {
                    pending.write(wav0, 44, wav0.length - 44);
                    byte[] all = pending.toByteArray();
                    pending.reset();
                    // 너무 길면 뒤쪽 30초만
                    int max = 16000 * 2 * 30;
                    if (all.length > max) all = java.util.Arrays.copyOfRange(all, all.length - max, all.length);
                    wav = AudioQ.wav(all);
                } else wav = wav0;
            }
            final int seq;
            synchronized (nextIn) { seq = nextIn[0]++; }
            final String context;
            synchronized (prev) { context = prev.length() > 200 ? prev.substring(prev.length() - 200) : prev.toString(); }
            pool.submit(() -> {
                String prompt = "첨부 오디오는 뉴스, 강의, 방송 같은 연속된 말의 짧은 한 조각(몇 초)이야.\n"
                        + (summary ? "들린 내용의 핵심만 자연스러운 한국어로 아주 짧게 전해 줘.\n"
                                   : "들린 말을 자연스러운 한국어로 짧게 번역해 줘. 원래 한국어면 none.\n")
                        + "조각 앞뒤가 문장 중간에서 잘렸을 수 있어. 바로 앞 번역: \"" + context + "\"\n"
                        + "앞 번역과 겹치는 내용은 빼고 이어지는 부분만. 설명, 따옴표, 기호 없이 번역문만.\n"
                        + "말소리가 없거나 음악/잡음뿐이면 none 이라고만 답해.";
                String t;
                try { t = core.askModel(prompt, null, wav).trim(); }
                catch (Exception e) {
                    String m = String.valueOf(e.getMessage());
                    if (m.contains("429") || m.contains("503") || m.toLowerCase().contains("quota") || m.contains("RESOURCE_EXHAUSTED")) {
                        coolUntil[0] = System.currentTimeMillis() + 10000;
                        core.log("무료 사용량 한도/혼잡 → 10초 쉬었다가 모아서 번역할게요");
                    } else core.log("이 조각은 건너뜀: " + m);
                    t = "";
                }
                results.put(seq, t);
            });
        }, core::log));
        rec.start();
        int nextOut = 0;
        long waitSince = System.currentTimeMillis();
        StringBuilder shown = new StringBuilder();
        while (running) {
            String t = results.remove(nextOut);
            if (t == null) {
                int made; synchronized (nextIn) { made = nextIn[0]; }
                // 너무 오래 안 오는 조각은 건너뜀
                if (nextOut < made && System.currentTimeMillis() - waitSince > 10000) { nextOut++; waitSince = System.currentTimeMillis(); continue; }
                if (System.currentTimeMillis() - lastVoice[0] > 120000) { core.say("소리가 없어서 통역을 끝낼게요"); break; }
                Thread.sleep(100);
                continue;
            }
            nextOut++;
            waitSince = System.currentTimeMillis();
            if (t.isEmpty() || t.equalsIgnoreCase("none")) continue;
            synchronized (prev) { prev.append(' ').append(t); }
            shown.append(' ').append(t);
            core.answer(shown.length() > 800 ? "…" + shown.substring(shown.length() - 800) : shown.toString().trim());
            int backlog = results.size();
            core.speakQueuedFast(t, Locale.KOREAN, backlog > 0 || core.isSpeaking());
        }
        pool.shutdownNow();
        return shown.toString().trim();
    }
}
