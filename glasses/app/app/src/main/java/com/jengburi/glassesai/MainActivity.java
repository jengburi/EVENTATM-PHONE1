package com.jengburi.glassesai;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements AppCore.Ui {
    static final float[] RATES = {0.9f, 1.0f, 1.3f, 1.6f};
    static final String[] RATE_NAMES = {"천천히", "보통", "빠르게", "아주 빠르게"};

    private AppCore core;
    private EditText keyEdit, modelEdit, promptEdit, triggerEdit, playEdit, nextEdit, prevEdit;
    private Button goBtn, againBtn, voiceBtn, serviceBtn;
    private CheckBox voiceBox, mediaBox, followBox, galleryBox, geminiEarBox, glassesMicBox;
    private RadioGroup modeGroup, rateGroup;
    private ImageView photo;
    private TextView answer, logView;
    private LinearLayout historyList;
    private ScrollView scroll;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        core = AppCore.get(this);
        buildUi();
        askPermissions();
    }

    @Override protected void onResume() {
        super.onResume();
        core.ui = this;
        loadFields();
        logView.setText(core.logText());
        answer.setText(core.lastAnswer);
        if (core.lastJpeg != null) onPhoto(core.lastJpeg);
        onBusy(core.busy);
        updateServiceBtn();
        refreshHistory();
    }

    @Override protected void onPause() {
        super.onPause();
        saveFields();
        core.ui = null;
    }

    private void buildUi() {
        int pad = dp(16);
        scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);

        TextView title = new TextView(this);
        title.setText("안경 AI (S1Pro + Gemini)");
        title.setTextSize(22);
        col.addView(title);

        goBtn = bigButton(col, "📸  안경으로 찍고 설명 듣기", 96);
        goBtn.setOnClickListener(v -> { saveFields(); core.trigger("화면 버튼"); });

        LinearLayout row = new LinearLayout(this);
        col.addView(row);
        againBtn = rowButton(row, "🔁 다시 묻기");
        againBtn.setOnClickListener(v -> { saveFields(); core.askAgain(); });
        Button replayBtn = rowButton(row, "🔊 다시 듣기");
        replayBtn.setOnClickListener(v -> core.replay());
        Button stopBtn = rowButton(row, "🔇 멈추기");
        stopBtn.setOnClickListener(v -> core.stopSpeaking());

        voiceBtn = bigButton(col, "🎤 사진 없이 말로만 물어보기", 56);
        voiceBtn.setOnClickListener(v -> { saveFields(); core.voiceOnly("화면 버튼"); });

        answer = new TextView(this);
        answer.setTextSize(18);
        answer.setPadding(0, dp(12), 0, dp(12));
        answer.setTextIsSelectable(true);
        col.addView(answer);

        photo = new ImageView(this);
        photo.setAdjustViewBounds(true);
        col.addView(photo, new LinearLayout.LayoutParams(-1, -2));

        // --- 모드 ---
        col.addView(section("모드"));
        modeGroup = new RadioGroup(this);
        for (int i = 0; i < AppCore.MODE_NAMES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(AppCore.MODE_NAMES[i]);
            modeGroup.addView(rb);
        }
        modeGroup.setOnCheckedChangeListener((g, id) -> {
            int m = id - 1000;
            core.prefs.edit().putInt("mode", m).apply();
            promptEdit.setVisibility(m == AppCore.MODE_NAMES.length - 1 ? View.VISIBLE : View.GONE);
        });
        col.addView(modeGroup);
        promptEdit = new EditText(this);
        promptEdit.setMinLines(3);
        promptEdit.setGravity(Gravity.TOP);
        promptEdit.setHint("AI에게 할 말을 직접 적어 주세요");
        col.addView(promptEdit);

        // --- 대화 ---
        col.addView(section("대화"));
        voiceBox = check(col, "🎤 찍은 뒤 말로 질문하기 (안경 마이크)", "voice", false);
        followBox = check(col, "💬 연속 대화 (대답 뒤 \"더 궁금한 거 있으세요?\" → 이어서 질문, \"없어\"라고 하면 끝)", "followUp", false);
        geminiEarBox = check(col, "👂 Gemini가 목소리를 직접 듣기 (추천, 웅얼거려도 잘 알아들음)", "geminiListen", true);
        glassesMicBox = check(col, "👓 안경 마이크 사용 (끄면 휴대폰 마이크)", "glassesMic", true);
        galleryBox = check(col, "🖼 찍은 사진을 휴대폰 갤러리에도 저장 (Pictures/GlassesAI)", "gallery", true);
        col.addView(label("읽는 속도"));
        rateGroup = new RadioGroup(this);
        rateGroup.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < RATES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(2000 + i);
            rb.setText(RATE_NAMES[i]);
            rateGroup.addView(rb);
        }
        rateGroup.setOnCheckedChangeListener((g, id) -> core.prefs.edit().putFloat("rate", RATES[id - 2000]).apply());
        col.addView(rateGroup);

        // --- 안경 버튼 ---
        col.addView(section("안경 버튼으로 실행"));
        serviceBtn = new Button(this);
        serviceBtn.setOnClickListener(v -> {
            saveFields();
            if (GlassesService.running) GlassesService.stop(this); else GlassesService.start(this);
            serviceBtn.postDelayed(this::updateServiceBtn, 500);
        });
        col.addView(serviceBtn, new LinearLayout.LayoutParams(-1, -2));
        TextView help = label("버튼에 붙일 수 있는 동작: 자동 / 번역 / 문제 풀이 / 설명 / 쟁부리우드 제품 / 직접 입력 / 다시듣기 / 말로만\n"
                + "(비워 두면 위에서 고른 모드, AI가 읽는 중에 누르면 읽기 멈춤)");
        help.setTextColor(Color.GRAY);
        col.addView(help);
        col.addView(label("안경 블루투스 신호 → 동작  (예: 97  또는  97:번역, 51:다시듣기)"));
        triggerEdit = edit(col);
        mediaBox = check(col, "안경 터치(재생 신호)도 사용", "mediaKey", true);
        col.addView(label("재생/일시정지 신호 → 동작 (비우면 현재 모드)"));
        playEdit = edit(col);
        col.addView(label("다음 곡 신호 → 동작"));
        nextEdit = edit(col);
        col.addView(label("이전 곡 신호 → 동작"));
        prevEdit = edit(col);
        TextView note = label("※ 버튼 설정을 바꾼 뒤에는 대기를 껐다 켜 주세요. 어떤 버튼이 어떤 신호인지는 아래 진행 기록에 찍혀요.");
        note.setTextColor(Color.GRAY);
        col.addView(note);

        // --- 기록 ---
        col.addView(section("지난 기록 (누르면 크게 보기)"));
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        col.addView(historyList);

        // --- 설정 ---
        col.addView(section("설정"));
        col.addView(label("Gemini API 키"));
        keyEdit = new EditText(this);
        keyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(keyEdit);
        col.addView(label("모델 이름 (쉼표로 여러 개, 앞에서부터 시도)"));
        modelEdit = edit(col);

        col.addView(section("진행 기록"));
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextColor(Color.GRAY);
        logView.setTextIsSelectable(true);
        col.addView(logView);

        setContentView(scroll);
    }

    private Button bigButton(LinearLayout col, String text, int minDp) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(minDp > 80 ? 20 : 16);
        b.setMinHeight(dp(minDp));
        col.addView(b, new LinearLayout.LayoutParams(-1, -2));
        return b;
    }

    private Button rowButton(LinearLayout row, String text) {
        Button b = new Button(this);
        b.setText(text);
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        return b;
    }

    private CheckBox check(LinearLayout col, String text, String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTag(new Object[]{key, def});
        c.setOnCheckedChangeListener((v, on) -> core.prefs.edit().putBoolean(key, on).apply());
        col.addView(c);
        return c;
    }

    private EditText edit(LinearLayout col) {
        EditText e = new EditText(this);
        e.setSingleLine();
        col.addView(e);
        return e;
    }

    private void loadCheck(CheckBox c) {
        Object[] t = (Object[]) c.getTag();
        c.setChecked(core.prefs.getBoolean((String) t[0], (Boolean) t[1]));
    }

    private void loadFields() {
        keyEdit.setText(core.prefs.getString("key", ""));
        modelEdit.setText(core.models());
        promptEdit.setText(core.prefs.getString("customPrompt", AppCore.MODE_PROMPTS[0]));
        triggerEdit.setText(core.triggerCodes());
        playEdit.setText(core.prefs.getString("actPlay", ""));
        nextEdit.setText(core.prefs.getString("actNext", "번역"));
        prevEdit.setText(core.prefs.getString("actPrev", "다시듣기"));
        loadCheck(voiceBox); loadCheck(followBox); loadCheck(galleryBox); loadCheck(mediaBox);
        loadCheck(geminiEarBox); loadCheck(glassesMicBox);
        modeGroup.check(1000 + core.mode());
        promptEdit.setVisibility(core.mode() == AppCore.MODE_NAMES.length - 1 ? View.VISIBLE : View.GONE);
        float r = core.prefs.getFloat("rate", 1.0f);
        int ri = 1;
        for (int i = 0; i < RATES.length; i++) if (Math.abs(RATES[i] - r) < 0.01) ri = i;
        rateGroup.check(2000 + ri);
    }

    private void saveFields() {
        core.prefs.edit()
                .putString("key", keyEdit.getText().toString().trim())
                .putString("model", modelEdit.getText().toString().trim())
                .putString("customPrompt", promptEdit.getText().toString().trim())
                .putString("triggers", triggerEdit.getText().toString().trim())
                .putString("actPlay", playEdit.getText().toString().trim())
                .putString("actNext", nextEdit.getText().toString().trim())
                .putString("actPrev", prevEdit.getText().toString().trim())
                .apply();
    }

    private void updateServiceBtn() {
        serviceBtn.setText(GlassesService.running ? "🟢 안경 버튼 대기 중 (누르면 끄기)" : "⚪ 안경 버튼 대기 켜기");
    }

    private void refreshHistory() {
        historyList.removeAllViews();
        List<History.Entry> list = History.list(this, 30);
        if (list.isEmpty()) { historyList.addView(label("아직 기록이 없어요")); return; }
        for (History.Entry e : list) {
            TextView t = new TextView(this);
            String q = e.question == null || e.question.isEmpty() ? "" : " · 질문: " + e.question;
            String a = e.answer.length() > 60 ? e.answer.substring(0, 60) + "…" : e.answer;
            t.setText((e.photo != null ? "📷 " : "💬 ") + e.time + " [" + e.mode + "]" + q + "\n" + a);
            t.setPadding(0, dp(8), 0, dp(8));
            t.setOnClickListener(v -> {
                answer.setText(e.answer);
                core.lastAnswer = e.answer;
                if (e.photo != null) {
                    Bitmap bmp = BitmapFactory.decodeFile(e.photo.getAbsolutePath());
                    photo.setImageBitmap(bmp);
                    try { core.lastJpeg = java.nio.file.Files.readAllBytes(e.photo.toPath()); } catch (Exception ignored) { }
                }
                scroll.smoothScrollTo(0, 0);
            });
            historyList.addView(t);
        }
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(17);
        t.setPadding(0, dp(22), 0, dp(4));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
            need.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> missing = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
    }

    // ---------- AppCore.Ui ----------
    @Override public void onLog(String s) { runOnUiThread(() -> logView.setText(core.logText())); }
    @Override public void onAnswer(String s) { runOnUiThread(() -> answer.setText(s)); }
    @Override public void onPhoto(byte[] jpeg) {
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        runOnUiThread(() -> photo.setImageBitmap(bmp));
    }
    @Override public void onBusy(boolean b) {
        runOnUiThread(() -> { goBtn.setEnabled(!b); againBtn.setEnabled(!b); voiceBtn.setEnabled(!b); });
    }
    @Override public void onHistoryChanged() { runOnUiThread(this::refreshHistory); }
}
