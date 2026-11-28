package com.jengburi.tutor;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 쟁부리 AI 과외 — 삼각대에 꽂은 USB 웹캠이 책상 위 문제지를 보고, 과외 선생님처럼 도와준다.
 * 질문하기 · 힌트(단계별) · 풀이 · 채점 · 지켜보기(알아서 실수 짚기) · 오늘 리포트
 */
public class TutorActivity extends Activity {
    static final int BG = 0xFF0F1115, CARD = 0xFF1A1D24, CARD2 = 0xFF252A35, ACC = 0xFFFFB020, ACC_DARK = 0xFF2A2210;
    static final int TXT = 0xFFF2F3F5, SUB = 0xFF9AA0AA, GREEN = 0xFF3DDC84, RED = 0xFFFF6B6B, BLUE = 0xFF5B9BFF, PURPLE = 0xFFB18CFF;
    static final String[] LEVELS = {"초등 저학년", "초등 고학년", "중학생", "고등학생", "성인"};
    static final int[] WATCH_SEC = {10, 20, 30, 60};

    private SharedPreferences prefs;
    private Cam cam;
    private TextToSpeech tts;
    private boolean ttsReady;
    private volatile boolean busy, watching;
    private int hintLevel = 0;
    private String lastWatch = "";
    private final List<String> session = new ArrayList<>();
    private final long sessionStart = System.currentTimeMillis();
    private int graded = 0, gradedRight = 0;

    private TextView status, answer, logView, watchLabel, keyStatus;
    private View watchBtn;
    private EditText keyEdit, modelEdit;
    private final StringBuilder logBuf = new StringBuilder();
    private final List<View> actionBtns = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tutor", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        tts = new TextToSpeech(this, s -> { if (s == TextToSpeech.SUCCESS) { tts.setLanguage(Locale.KOREAN); ttsReady = true; } });
        cam = new Cam(this::log, (s, ok) -> runOnUiThread(() -> { status.setText(s); status.setTextColor(ok ? GREEN : SUB); }));
        buildUi();
        List<String> need = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO})
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cam.start();
        updateKey();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cam.start();
    }

    @Override protected void onPause() {
        super.onPause();
        saveKey();
        watching = false;
        cam.stop();
        if (watchLabel != null) watchLabel.setText("지켜보기 켜기");
    }

    @Override protected void onDestroy() { if (tts != null) tts.shutdown(); super.onDestroy(); }

    // ============================== 화면 ==============================
    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(18), dp(16), dp(28));
        sv.addView(col);

        col.addView(text("쟁부리 AI 과외", 22, TXT, true));
        status = text("웹캠을 꽂아 주세요 (USB-C / OTG)", 13, SUB, true);
        status.setPadding(0, dp(4), 0, dp(8));
        col.addView(status);

        // 웹캠 화면 (16:9)
        FrameLayout pv = new FrameLayout(this);
        pv.setBackground(round(0xFF000000, 18));
        pv.setClipToOutline(true);
        SurfaceView surf = new SurfaceView(this);
        surf.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { cam.setSurface(h.getSurface()); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) { cam.setSurface(null); }
        });
        pv.addView(surf, new FrameLayout.LayoutParams(-1, -1));
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        col.addView(pv, new LinearLayout.LayoutParams(-1, w * 9 / 16));

        // 큰 버튼: 질문하기
        View ask = bigTile(col, "🎤", "선생님께 질문하기", "\"이 문제 어떻게 풀어?\" \"이 단어 뜻이 뭐야?\"");
        ask.setOnClickListener(v -> run("질문", this::askVoice));
        actionBtns.add(ask);

        LinearLayout r1 = row(col);
        View hint = tile(r1, "💡", "힌트", "정답 말고 한 단계씩", ACC);
        hint.setOnClickListener(v -> run("힌트", this::giveHint));
        View sol = tile(r1, "📖", "풀이", "처음부터 차근차근", BLUE);
        sol.setOnClickListener(v -> run("풀이", this::solve));
        LinearLayout r2 = row(col);
        View grade = tile(r2, "✅", "채점", "다 푼 문제지 보여주기", GREEN);
        grade.setOnClickListener(v -> run("채점", this::grade));
        watchBtn = tile(r2, "👀", "지켜보기 켜기", "풀다가 실수하면 알려줘요", PURPLE);
        watchLabel = (TextView) ((LinearLayout) watchBtn).getChildAt(1);
        watchBtn.setOnClickListener(v -> toggleWatch());
        actionBtns.add(hint); actionBtns.add(sol); actionBtns.add(grade);

        LinearLayout r3 = new LinearLayout(this);
        col.addView(r3);
        smallBtn(r3, "🔄 새 문제").setOnClickListener(v -> { hintLevel = 0; lastWatch = ""; toast("새 문제로 넘어갈게요 (힌트 처음부터)"); });
        smallBtn(r3, "🔇 멈추기").setOnClickListener(v -> { if (ttsReady) tts.stop(); Mic.cancel = true; });
        smallBtn(r3, "📝 오늘 리포트").setOnClickListener(v -> run("리포트", this::report));

        LinearLayout ac = card(col);
        ac.addView(text("선생님", 13, ACC, true));
        answer = text("웹캠으로 문제지를 비추고 버튼을 눌러 보세요.", 17, TXT, false);
        answer.setPadding(0, dp(8), 0, 0);
        answer.setLineSpacing(0, 1.25f);
        answer.setTextIsSelectable(true);
        ac.addView(answer);

        // 설정
        LinearLayout st = card(col);
        st.addView(text("⚙️ 설정", 15, TXT, true));
        keyStatus = text("", 13, SUB, true);
        keyStatus.setPadding(0, dp(8), 0, 0);
        st.addView(keyStatus);
        st.addView(label("Gemini API 키 (쟁부리 AI 안경 앱과 같은 키를 써도 돼요)"));
        keyEdit = edit(st);
        keyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyEdit.setText(prefs.getString("key", ""));
        LinearLayout kr = new LinearLayout(this);
        st.addView(kr);
        smallBtn(kr, "📋 키 붙여넣기").setOnClickListener(v -> pasteKey());
        smallBtn(kr, "✅ 키 테스트").setOnClickListener(v -> testKey());
        st.addView(label("모델"));
        modelEdit = edit(st);
        modelEdit.setText(prefs.getString("model", "gemini-3.8-flash, gemini-flash-lite-latest"));
        st.addView(label("학생 수준"));
        chips(st, LEVELS, 3, prefs.getInt("level", 2), i -> prefs.edit().putInt("level", i).apply());
        st.addView(label("지켜보기 간격"));
        String[] ws = {"10초", "20초", "30초", "1분"};
        chips(st, ws, 4, prefs.getInt("watchIdx", 1), i -> prefs.edit().putInt("watchIdx", i).apply());
        Switch ans = sw(st, "질문하면 정답까지 바로 알려주기 (끄면 스스로 풀게 유도)", "giveAnswer", false);
        Switch spk = sw(st, "선생님 목소리로 읽어 주기", "speak", true);

        LinearLayout lg = card(col);
        lg.addView(text("진행 기록", 13, SUB, true));
        logView = text("", 12, SUB, false);
        logView.setTextIsSelectable(true);
        lg.addView(logView);
        TextView ver = text("쟁부리 AI 과외 1.0 · 개인이 만든 무료 앱 · AI 대답은 틀릴 수 있어요", 11, SUB, false);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(12), 0, 0);
        col.addView(ver);
        setContentView(sv);
    }

    // ============================== 동작 ==============================
    interface Job { String run() throws Exception; }

    private void run(String name, Job job) {
        if (busy) { toast("잠깐만요, 아직 생각 중이에요"); return; }
        saveKey();
        if (key().isEmpty()) { toast("먼저 설정에서 Gemini API 키를 넣어 주세요"); return; }
        busy = true;
        setButtons(false);
        answer.setText(name + " 중…");
        new Thread(() -> {
            try {
                String r = job.run();
                if (r != null && !r.isEmpty()) show(r, true);
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                log("오류: " + m);
                show(m.contains("429") ? "잠깐 사용 한도에 걸렸어요. 1분쯤 뒤에 다시 해 주세요." : "오류가 났어요: " + m, true);
            } finally {
                busy = false;
                runOnUiThread(() -> setButtons(true));
            }
        }).start();
    }

    private byte[] needPhoto() throws Exception {
        byte[] j = cam.snapshot();
        if (j == null) throw new Exception("웹캠 화면이 아직 없어요. 웹캠이 꽂혀 있는지 확인해 주세요");
        return j;
    }

    private String base() {
        return "너는 다정하고 실력 있는 1:1 과외 선생님 '쟁부리 선생님'이야. 학생 수준: " + LEVELS[prefs.getInt("level", 2)] + ".\n"
                + "첨부 사진은 책상 위를 비추는 웹캠 화면이야 (문제지, 교과서, 학생이 손으로 쓴 풀이).\n"
                + "귀로 들을 말이니 마크다운·기호 없이, 그 수준에 맞는 쉬운 말로 짧게 말해 줘. 수식은 말로 읽듯이 (예: x의 제곱).\n";
    }

    private String askVoice() throws Exception {
        say("네, 말씀하세요");
        waitSpeech(4);
        byte[] wav = Mic.record(8, 20, this::log);
        if (wav == null) return "못 들었어요. 다시 눌러서 말씀해 주세요.";
        byte[] j = needPhoto();
        boolean give = prefs.getBoolean("giveAnswer", false);
        String p = base() + "학생이 음성으로 질문했어(첨부 오디오). 질문을 다시 적지 말고 바로 답해.\n"
                + (give ? "물어본 것에 정답과 풀이를 알려 줘."
                        : "문제의 정답을 바로 알려주지 마. 스스로 풀 수 있게 생각할 거리나 다음 한 걸음을 질문으로 던져 줘. 개념 설명이나 단어 뜻 같은 건 바로 알려줘도 돼.")
                + history();
        String a = ask(p, j, wav);
        session.add("질문 → " + a);
        return a;
    }

    private String giveHint() throws Exception {
        hintLevel = Math.min(hintLevel + 1, 3);
        String[] step = {"", "1단계: 어떤 개념·공식을 떠올려야 하는지만", "2단계: 풀이의 첫 걸음만 (계산은 학생에게 맡기기)", "3단계: 거의 다 왔을 때의 마지막 힌트 (정답 숫자는 말하지 않기)"};
        byte[] j = needPhoto();
        String p = base() + "학생이 지금 풀고 있는 문제(사진에서 손이나 펜이 있는 곳, 또는 풀이가 진행 중인 문제)에 대한 힌트를 줘.\n"
                + "힌트 " + step[hintLevel] + ". 한두 문장.\n" + (hintLevel >= 3 ? "다음에도 막히면 '풀이' 버튼을 눌러 보라고 덧붙여.\n" : "") + history();
        String a = ask(p, j, null);
        session.add("힌트" + hintLevel + " → " + a);
        return "💡 힌트 " + hintLevel + "\n" + a;
    }

    private String solve() throws Exception {
        byte[] j = needPhoto();
        String p = base() + "학생이 보고 있는 문제를 처음부터 차근차근 풀어 줘. 단계마다 왜 그렇게 하는지 한 줄씩, 마지막에 정답. 문제가 여러 개면 가장 위(또는 학생이 가리키는) 문제 하나만.";
        String a = ask(p, j, null);
        hintLevel = 0;
        session.add("풀이 → " + a);
        return "📖 풀이\n" + a;
    }

    private String grade() throws Exception {
        byte[] j = needPhoto();
        String p = base() + "사진 속 문제지를 채점해 줘. 학생이 쓴 답과 정답을 비교해서 문제 번호마다 맞음/틀림을 말하고, 틀린 문제는 왜 틀렸는지 한 줄로 알려 줘. "
                + "마지막에 칭찬 한마디. 맨 마지막 줄에는 [점수:맞은개수/전체개수] 형식으로만 적어 줘. 학생 답이 안 보이면 [점수:0/0].";
        String a = ask(p, j, null);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[점수\\s*[:：]\\s*(\\d+)\\s*/\\s*(\\d+)\\]").matcher(a);
        String clean = a.replaceAll("\\[점수[^\\]]*\\]", "").trim();
        String head = "✅ 채점";
        if (m.find()) {
            int ok = Integer.parseInt(m.group(1)), all = Integer.parseInt(m.group(2));
            if (all > 0) {
                graded += all; gradedRight += ok;
                head = "✅ 채점 · " + ok + " / " + all + " 맞음";
                if (ok < all) saveWrongNote(j, clean); // 틀린 문제가 있으면 오답 노트로
            }
        }
        session.add("채점 → " + head + " / " + clean);
        return head + "\n" + clean;
    }

    private void toggleWatch() {
        saveKey();
        if (key().isEmpty()) { toast("먼저 설정에서 Gemini API 키를 넣어 주세요"); return; }
        watching = !watching;
        watchLabel.setText(watching ? "■ 지켜보기 끄기" : "지켜보기 켜기");
        if (!watching) { log("지켜보기 끝"); return; }
        int sec = WATCH_SEC[prefs.getInt("watchIdx", 1)];
        say("지켜볼게요. 풀다가 실수하면 알려 줄게요");
        log("👀 지켜보기 시작 (" + sec + "초마다)");
        new Thread(() -> {
            while (watching) {
                try { Thread.sleep(sec * 1000L); } catch (InterruptedException ignored) { }
                if (!watching) break;
                if (busy || (ttsReady && tts.isSpeaking())) continue;
                byte[] j = cam.snapshot();
                if (j == null) continue;
                try {
                    String p = base() + "너는 학생 옆에서 조용히 풀이를 지켜보는 중이야. 사진 속 학생의 손글씨 풀이를 봐.\n"
                            + "- 계산 실수나 개념 실수가 분명히 보이면: 어디가 틀렸는지 한 문장으로만 짚어 줘 (정답은 말하지 말고).\n"
                            + "- 같은 곳에서 오래 막혀 보이면(바로 전과 풀이가 거의 같으면): 짧은 격려와 아주 작은 힌트 한 문장.\n"
                            + "- 그 외(잘 풀고 있거나, 풀이가 안 보이거나, 확실하지 않으면): 다른 말 없이 PASS 라고만 답해.\n"
                            + (lastWatch.isEmpty() ? "" : "바로 전에 네가 한 말: \"" + lastWatch + "\" (같은 말 반복 금지)\n");
                    String a = Gemini.askWithRetry(key(), models(), p, j, null, this::log).trim();
                    if (a.toUpperCase().startsWith("PASS") || a.length() < 2) { log("👀 잘 풀고 있어요"); continue; }
                    lastWatch = a;
                    session.add("지켜보기 → " + a);
                    show("👀 " + a, true);
                } catch (Exception e) {
                    log("지켜보기 오류: " + e.getMessage());
                    if (String.valueOf(e.getMessage()).contains("429")) { try { Thread.sleep(30000); } catch (InterruptedException ignored) { } }
                }
            }
        }).start();
    }

    private String report() throws Exception {
        if (session.isEmpty()) return "아직 오늘 공부한 기록이 없어요. 문제를 풀어 보고 다시 눌러 주세요.";
        int mins = (int) ((System.currentTimeMillis() - sessionStart) / 60000);
        StringBuilder sb = new StringBuilder();
        for (String s : session) sb.append("- ").append(s.length() > 300 ? s.substring(0, 300) : s).append('\n');
        String p = "너는 과외 선생님이야. 다음은 오늘 공부 시간 동안 있었던 일이야 (총 " + mins + "분, 채점 " + gradedRight + "/" + graded + ").\n" + sb
                + "\n학부모님께 보낼 짧은 공부 리포트를 써 줘: 오늘 한 것, 잘한 점, 약한 부분, 다음에 할 것. 5문장 이내, 따뜻하게. 마크다운 없이.";
        String a = Gemini.askWithRetry(key(), models(), p, null, null, this::log).trim();
        String full = "📝 오늘의 공부 리포트 (" + new SimpleDateFormat("M월 d일", Locale.KOREA).format(new Date()) + ", " + mins + "분)\n" + a;
        return full;
    }

    /** 틀린 문제가 있는 문제지 사진 + 채점 내용을 갤러리(Pictures/AI과외_오답노트)에 저장 */
    private void saveWrongNote(byte[] jpeg, String note) {
        try {
            String name = "오답_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date()) + ".jpg";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AI과외_오답노트");
            v.put(MediaStore.Images.Media.DESCRIPTION, note.length() > 250 ? note.substring(0, 250) : note);
            android.net.Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u != null) try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(jpeg); }
            log("📝 오답 노트에 저장 (갤러리 → AI과외_오답노트)");
        } catch (Exception e) { log("오답 노트 저장 실패: " + e.getMessage()); }
    }

    private String history() {
        if (session.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n(최근 대화: ");
        for (int i = Math.max(0, session.size() - 3); i < session.size(); i++) {
            String s = session.get(i);
            sb.append(s.length() > 150 ? s.substring(0, 150) : s).append(" / ");
        }
        return sb.append(")").toString();
    }

    private String ask(String prompt, byte[] jpeg, byte[] wav) throws Exception {
        log("쟁부리 선생님이 생각 중…");
        return Gemini.askWithRetry(key(), models(), prompt, jpeg, wav, this::log).trim();
    }

    private void show(String s, boolean speak) {
        runOnUiThread(() -> answer.setText(s));
        if (speak && prefs.getBoolean("speak", true)) say(s.replaceAll("^[💡📖✅👀📝]\\s*", ""));
    }

    private void say(String s) {
        if (!ttsReady) return;
        tts.setSpeechRate(1.05f);
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "t" + System.nanoTime());
    }

    private void waitSpeech(int sec) {
        long end = System.currentTimeMillis() + sec * 1000L;
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        while (ttsReady && tts.isSpeaking() && System.currentTimeMillis() < end) { try { Thread.sleep(100); } catch (InterruptedException ignored) { } }
    }

    // ============================== 키 ==============================
    private String key() { return prefs.getString("key", "").trim(); }
    private String models() { return prefs.getString("model", "gemini-3.8-flash, gemini-flash-lite-latest"); }

    private void saveKey() {
        if (keyEdit == null) return;
        prefs.edit().putString("key", keyEdit.getText().toString().replaceAll("\\s", ""))
                .putString("model", modelEdit.getText().toString().trim()).apply();
    }

    private void updateKey() {
        String k = key();
        if (k.isEmpty()) { keyStatus.setText("❌ API 키가 없어요 — 아래에 붙여넣어 주세요"); keyStatus.setTextColor(RED); return; }
        keyStatus.setText("🔑 키: " + (k.length() > 10 ? k.substring(0, 5) + "…" + k.substring(k.length() - 4) : k));
        keyStatus.setTextColor(TXT);
    }

    private void pasteKey() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            CharSequence c = cm.hasPrimaryClip() ? cm.getPrimaryClip().getItemAt(0).coerceToText(this) : null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(AIza[0-9A-Za-z_\\-]{20,}|AQ\\.[0-9A-Za-z_\\-\\.]{20,})").matcher(c == null ? "" : c);
            if (!m.find()) { toast("복사한 글에서 키를 못 찾았어요"); return; }
            keyEdit.setText(m.group(1));
            saveKey(); updateKey();
            toast("키를 넣었어요");
        } catch (Exception e) { toast("붙여넣기 실패"); }
    }

    private void testKey() {
        saveKey(); updateKey();
        keyStatus.setText("⏳ 키 확인 중…");
        new Thread(() -> {
            String msg; int c;
            try { Gemini.askWithRetry(key(), models(), "'연결 성공'이라고만 답해.", null, null, this::log); msg = "✅ 키가 잘 돼요!"; c = GREEN; }
            catch (Exception e) { msg = "❌ " + e.getMessage(); c = RED; }
            String fm = msg; int fc = c;
            runOnUiThread(() -> { keyStatus.setText(fm); keyStatus.setTextColor(fc); });
        }).start();
    }

    // ============================== 꾸미기 ==============================
    private void log(String s) {
        synchronized (logBuf) {
            logBuf.insert(0, new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date()) + " " + s + "\n");
            if (logBuf.length() > 2500) logBuf.setLength(2500);
        }
        runOnUiThread(() -> { if (logView != null) logView.setText(logBuf.toString()); });
    }

    private void setButtons(boolean on) { for (View v : actionBtns) { v.setEnabled(on); v.setAlpha(on ? 1f : 0.45f); } }
    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, int r) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(r)); return g; }
    private android.graphics.drawable.Drawable press(int color, int r) { return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(color, r), round(0xFFFFFFFF, r)); }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
    private TextView label(String s) { TextView t = text(s, 13, SUB, true); t.setPadding(0, dp(14), 0, dp(4)); return t; }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(round(CARD, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8); lp.bottomMargin = dp(4);
        parent.addView(c, lp);
        return c;
    }

    private LinearLayout row(LinearLayout parent) { LinearLayout r = new LinearLayout(this); parent.addView(r, new LinearLayout.LayoutParams(-1, -2)); return r; }

    private View bigTile(LinearLayout parent, String emoji, String title, String sub) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(20), dp(20), dp(20), dp(20));
        t.setBackground(press(ACC, 24));
        t.addView(text(emoji, 32, 0xFF1A1205, false));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(14), 0, 0, 0);
        tx.addView(text(title, 21, 0xFF1A1205, true));
        tx.addView(text(sub, 12, 0xB01A1205, false));
        t.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12); lp.bottomMargin = dp(4);
        parent.addView(t, lp);
        return t;
    }

    private View tile(LinearLayout row, String emoji, String name, String ex, int color) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = round(CARD, 18);
        bg.setStroke(dp(1), (color & 0x00FFFFFF) | 0x55000000);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf((color & 0x00FFFFFF) | 0x44000000), bg, round(0xFFFFFFFF, 18)));
        t.addView(text(emoji, 24, TXT, false));
        TextView n = text(name, 16, TXT, true); n.setPadding(0, dp(4), 0, 0); t.addView(n);
        TextView e = text(ex, 12, color, false); e.setPadding(0, dp(2), 0, 0); t.addView(e);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(t, lp);
        return t;
    }

    private TextView smallBtn(LinearLayout row, String s) {
        TextView t = text(s, 14, TXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(4), dp(12), dp(4), dp(12));
        t.setBackground(press(CARD2, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(t, lp);
        return t;
    }

    private EditText edit(LinearLayout parent) {
        EditText e = new EditText(this);
        e.setSingleLine(); e.setTextColor(TXT); e.setTextSize(15);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(round(CARD2, 12));
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private Switch sw(LinearLayout parent, String s, String key, boolean def) {
        Switch c = new Switch(this);
        c.setText(s); c.setTextColor(TXT); c.setTextSize(14);
        c.setPadding(0, dp(10), 0, dp(10));
        c.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ACC, 0xFF8A8F99}));
        c.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{0x88FFB020, 0xFF3A3F4A}));
        c.setChecked(prefs.getBoolean(key, def));
        c.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        parent.addView(c, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    interface OnPick { void pick(int i); }

    private void chips(LinearLayout parent, String[] items, int perRow, int sel, OnPick cb) {
        List<TextView> vs = new ArrayList<>();
        LinearLayout r = null;
        for (int i = 0; i < items.length; i++) {
            if (i % perRow == 0) { r = new LinearLayout(this); parent.addView(r, new LinearLayout.LayoutParams(-1, -2)); }
            TextView t = text(items[i], 13, TXT, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(4), dp(10), dp(4), dp(10));
            final int idx = i;
            t.setOnClickListener(v -> { for (int k = 0; k < vs.size(); k++) paint(vs.get(k), k == idx); cb.pick(idx); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            r.addView(t, lp);
            vs.add(t);
        }
        if (items.length % perRow != 0) for (int k = items.length % perRow; k < perRow; k++) r.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        for (int k = 0; k < vs.size(); k++) paint(vs.get(k), k == sel);
    }

    private void paint(TextView t, boolean on) { t.setBackground(round(on ? ACC : CARD2, 12)); t.setTextColor(on ? 0xFF1A1205 : TXT); }
}
