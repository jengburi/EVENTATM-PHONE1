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
    private Button convoBtn, listenBtn;
    private CheckBox wakeBox;
    private RadioGroup gainGroup;
    private EditText wakeEdit;
    private EditText uploadUrlEdit, uploadKeyEdit;
    private CheckBox uploadBox;
    private RadioGroup glassesGroup;
    private final java.util.List<String[]> glassesList = new java.util.ArrayList<>();
    private RadioGroup langGroup;
    private CheckBox speakerBox, summaryBox, interpMicBox, liveBox;
    private CheckBox voiceBox, mediaBox, followBox, galleryBox, geminiEarBox, glassesMicBox, smartBox, smartFollowBox;
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
        refreshGlasses();
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
        title.setText("쟁부리 AI 안경 컨트롤러");
        title.setTextSize(22);
        col.addView(title);
        batteryView = new TextView(this);
        batteryView.setTextSize(16);
        batteryView.setOnClickListener(v -> new Thread(core.ble::requestBattery).start());
        col.addView(batteryView);
        batteryView.post(batteryTick);

        voiceBtn = bigButton(col, "🎤  그냥 물어보기\n(앞을 봐야 하면 알아서 찍어요)", 96);
        voiceBtn.setOnClickListener(v -> { saveFields(); core.smartAsk("화면 버튼"); });

        goBtn = bigButton(col, "📸  안경으로 찍고 설명 듣기", 64);
        goBtn.setOnClickListener(v -> { saveFields(); core.trigger("화면 버튼"); });

        LinearLayout row = new LinearLayout(this);
        col.addView(row);
        againBtn = rowButton(row, "🔁 다시 묻기");
        againBtn.setOnClickListener(v -> { saveFields(); core.askAgain(); });
        Button replayBtn = rowButton(row, "🔊 다시 듣기");
        replayBtn.setOnClickListener(v -> core.replay());
        Button stopBtn = rowButton(row, "🔇 멈추기");
        stopBtn.setOnClickListener(v -> core.stopSpeaking());


        LinearLayout irow = new LinearLayout(this);
        col.addView(irow);
        convoBtn = rowButton(irow, "🗣 대화 통역");
        convoBtn.setOnClickListener(v -> { saveFields(); if (core.interp.running) core.interp.stop(); else core.interp.start(false); });
        listenBtn = rowButton(irow, "📺 듣기 통역");
        listenBtn.setOnClickListener(v -> { saveFields(); if (core.interp.running) core.interp.stop(); else core.interp.start(true); });

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

        // --- 통역 ---
        col.addView(section("🌐 동시통역"));
        TextView ih = label("말로도 시킬 수 있어요: \"영어 통역해 줘\", \"지금 뉴스야 통역해 줘\", \"요약해서 통역해 줘\"\n끝낼 때는 안경 버튼 한 번 (대화 통역은 \"통역 그만\"이라고 말해도 돼요)");
        ih.setTextColor(Color.GRAY);
        col.addView(ih);
        col.addView(label("상대방 언어"));
        langGroup = new RadioGroup(this);
        LinearLayout lrow1 = new LinearLayout(this), lrow2 = new LinearLayout(this);
        for (int i = 0; i < Interpreter.LANG_NAMES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(3000 + i);
            rb.setText(Interpreter.LANG_NAMES[i]);
            langGroup.addView(rb);
        }
        langGroup.setOnCheckedChangeListener((g, id) -> core.prefs.edit().putString("interpLang", Interpreter.LANG_NAMES[id - 3000]).apply());
        col.addView(langGroup);
        speakerBox = check(col, "📢 내 말 번역은 휴대폰 스피커로 크게 (상대방이 듣게)", "interpSpeaker", true);
        summaryBox = check(col, "✂ 듣기 통역은 요약해서 (핵심만 짧게)", "interpSummary", false);
        liveBox = check(col, "⚡ 듣기 통역 실시간 모드 (Gemini Live, 지연 가장 적음 · 안 되면 자동으로 기존 방식)", "liveMode", true);
        interpMicBox = check(col, "👓 통역 때 안경 마이크 사용 (끄면 휴대폰 마이크, 상대방·TV 소리 듣기엔 휴대폰이 더 좋아요)", "interpGlassesMic", false);
        col.addView(label("마이크 소리 키우기 (작게 들리면 크게, 소리가 깨지면 작게)"));
        gainGroup = new RadioGroup(this);
        gainGroup.setOrientation(LinearLayout.HORIZONTAL);
        final float[] GAINS = {1f, 2f, 3f, 4f};
        for (int i = 0; i < GAINS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(5000 + i);
            rb.setText((int) GAINS[i] + "배");
            gainGroup.addView(rb);
        }
        float curGain = core.prefs.getFloat("micGain", 3.0f);
        for (int i = 0; i < GAINS.length; i++) if (Math.abs(GAINS[i] - curGain) < 0.01) gainGroup.check(5000 + i);
        gainGroup.setOnCheckedChangeListener((g, id) -> core.prefs.edit().putFloat("micGain", GAINS[id - 5000]).apply());
        col.addView(gainGroup);

        // --- 대화 ---
        col.addView(section("대화"));
        smartBox = check(col, "👓 안경 버튼 = 그냥 물어보기 (끄면 = 바로 찍고 설명)", "buttonSmart", true);
        smartFollowBox = check(col, "🔁 그냥 물어보기 뒤에 이어서 계속 대화 (말 안 하면 끝)", "smartFollow", true);
        voiceBox = check(col, "🎤 '찍고 설명' 때도 찍은 뒤 말로 질문하기", "voice", false);
        followBox = check(col, "💬 '찍고 설명' 뒤에도 이어서 대화", "followUp", false);
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

        // --- 드라이브 업로드 ---
        col.addView(section("📤 블로그·쇼츠용 드라이브 업로드"));
        TextView uh = label("찍은 사진과 AI 설명을 드라이브 '안경 촬영/날짜' 폴더에 자동으로 올려요. Claude에게 \"오늘 안경 사진으로 블로그랑 쇼츠 만들어 줘\"라고 하면 돼요.");
        uh.setTextColor(Color.GRAY);
        col.addView(uh);
        uploadBox = check(col, "찍을 때마다 자동으로 드라이브에 올리기", "autoUpload", true);
        col.addView(label("드라이브 업로드 주소 (앱스 스크립트 웹 앱 URL)"));
        uploadUrlEdit = edit(col);
        col.addView(label("비밀 코드"));
        uploadKeyEdit = edit(col);
        LinearLayout urow = new LinearLayout(this);
        col.addView(urow);
        Button testBtn = rowButton(urow, "연결 테스트");
        testBtn.setOnClickListener(v -> {
            saveFields();
            String url = uploadUrlEdit.getText().toString().trim();
            String key = uploadKeyEdit.getText().toString().trim();
            answer.setText("업로드 테스트 중…");
            new Thread(() -> {
                String msg;
                try { Uploader.upload(url, key, "연결테스트.txt", null, null, "안경 AI 앱 연결 테스트 성공"); msg = "✅ 드라이브 '안경 촬영' 폴더에 테스트 파일을 올렸어요!"; }
                catch (Exception e) { msg = "❌ " + e.getMessage(); }
                String m = msg;
                runOnUiThread(() -> answer.setText(m));
            }).start();
        });
        Button syncBtn = rowButton(urow, "안경 속 파일 전부 올리기");
        syncBtn.setOnClickListener(v -> { saveFields(); core.syncAllToDrive(); });

        // --- 호출어 ---
        col.addView(section("🗣 말로 부르기 (호출어)"));
        TextView wh = label("휴대폰이 계속 듣다가 호출어가 들리면 AI가 깨어나요. \"쟁부리야, 오늘 날씨 어때?\"처럼 이어서 말해도 돼요.\n※ 안경 버튼 대기가 켜져 있어야 동작하고, 배터리를 조금 더 써요.");
        wh.setTextColor(Color.GRAY);
        col.addView(wh);
        wakeBox = check(col, "호출어로 부르기 켜기", "wakeOn", false);
        wakeBox.setOnCheckedChangeListener((v, on) -> {
            core.prefs.edit().putBoolean("wakeOn", on).apply();
            if (GlassesService.running) { if (on) core.wakeWord.start(); else core.wakeWord.stop(); }
        });
        col.addView(label("호출어"));
        wakeEdit = edit(col);

        // --- 안경 고르기 ---
        col.addView(section("👓 안경 고르기 (S1Pro, M88 등)"));
        TextView gh = label("휴대폰과 블루투스로 페어링된 기기 목록이에요. 쓰실 안경을 고르세요.");
        gh.setTextColor(Color.GRAY);
        col.addView(gh);
        glassesGroup = new RadioGroup(this);
        col.addView(glassesGroup);
        Button refreshBtn = new Button(this);
        refreshBtn.setText("목록 새로고침");
        refreshBtn.setOnClickListener(v -> refreshGlasses());
        col.addView(refreshBtn);

        // --- 안경 버튼 ---
        col.addView(section("안경 버튼으로 실행"));
        serviceBtn = new Button(this);
        serviceBtn.setOnClickListener(v -> {
            saveFields();
            if (GlassesService.running) GlassesService.stop(this); else GlassesService.start(this);
            serviceBtn.postDelayed(this::updateServiceBtn, 500);
        });
        col.addView(serviceBtn, new LinearLayout.LayoutParams(-1, -2));
        TextView help = label("버튼에 붙일 수 있는 동작: 그냥물어보기 / 사진 / 통역 / 듣기통역 / 자동 / 번역 / 문제 풀이 / 설명 / 쟁부리우드 제품 / 직접 입력 / 다시듣기\n"
                + "(비워 두면 위 '안경 버튼' 설정대로, AI가 읽는 중에 누르면 읽기 멈춤)");
        help.setTextColor(Color.GRAY);
        col.addView(help);
        col.addView(label("안경 블루투스 신호 → 동작  (예: 97  또는  97:번역, 51:다시듣기)"));
        triggerEdit = edit(col);
        Button batBtn = new Button(this);
        batBtn.setText("🔋 화면 꺼져도 동작하게 (배터리 제한 해제)");
        batBtn.setOnClickListener(v -> askBatteryExemption());
        col.addView(batBtn, new LinearLayout.LayoutParams(-1, -2));
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

    private TextView batteryView;
    private final Runnable batteryTick = new Runnable() {
        @Override public void run() {
            int b = core.ble.battery;
            String conn = core.ble.isConnected() ? (core.ble.jianyun ? "M88 연결됨" : "S1Pro 연결됨") : "안경 연결 안 됨";
            batteryView.setText(conn + (b >= 0 ? "  ·  🔋 " + b + "%" + (core.ble.charging ? " (충전 중)" : "") : "  ·  🔋 확인 중 (누르면 새로고침)"));
            batteryView.postDelayed(this, 2000);
        }
    };

    private void loadFields() {
        keyEdit.setText(core.prefs.getString("key", ""));
        uploadUrlEdit.setText(core.prefs.getString("uploadUrl", ""));
        wakeEdit.setText(core.prefs.getString("wakeWord", "쟁부리야"));
        loadCheck(wakeBox);
        uploadKeyEdit.setText(core.prefs.getString("uploadKey", ""));
        loadCheck(uploadBox);
        modelEdit.setText(core.models());
        promptEdit.setText(core.prefs.getString("customPrompt", AppCore.MODE_PROMPTS[0]));
        triggerEdit.setText(core.triggerCodes());
        playEdit.setText(core.prefs.getString("actPlay", ""));
        nextEdit.setText(core.prefs.getString("actNext", "번역"));
        prevEdit.setText(core.prefs.getString("actPrev", "다시듣기"));
        loadCheck(voiceBox); loadCheck(followBox); loadCheck(galleryBox); loadCheck(mediaBox);
        loadCheck(geminiEarBox); loadCheck(glassesMicBox); loadCheck(smartBox); loadCheck(smartFollowBox);
        loadCheck(speakerBox); loadCheck(summaryBox); loadCheck(interpMicBox); loadCheck(liveBox);
        String lang = core.prefs.getString("interpLang", "자동");
        for (int i = 0; i < Interpreter.LANG_NAMES.length; i++) if (Interpreter.LANG_NAMES[i].equals(lang)) langGroup.check(3000 + i);
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
                .putString("uploadUrl", uploadUrlEdit.getText().toString().trim())
                .putString("wakeWord", wakeEdit.getText().toString().trim().isEmpty() ? "쟁부리야" : wakeEdit.getText().toString().trim())
                .putString("uploadKey", uploadKeyEdit.getText().toString().trim())
                .putString("model", modelEdit.getText().toString().trim())
                .putString("customPrompt", promptEdit.getText().toString().trim())
                .putString("triggers", triggerEdit.getText().toString().trim())
                .putString("actPlay", playEdit.getText().toString().trim())
                .putString("actNext", nextEdit.getText().toString().trim())
                .putString("actPrev", prevEdit.getText().toString().trim())
                .apply();
    }

    private void refreshGlasses() {
        glassesGroup.setOnCheckedChangeListener(null);
        glassesGroup.removeAllViews();
        glassesList.clear();
        glassesList.add(new String[]{"자동 (S1Pro / M88 이름으로 찾기)", ""});
        // S1Pro, M88 안경만 보여 주기 (다른 블루투스 기기는 숨김)
        for (String[] g : core.ble.bondedList()) {
            String n = g[0].toUpperCase().replace(" ", "");
            if (n.contains("S1PRO") || n.contains("M88")) glassesList.add(g);
        }
        String cur = core.prefs.getString("glassesAddr", "");
        for (int i = 0; i < glassesList.size(); i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(4000 + i);
            String[] g = glassesList.get(i);
            rb.setText(g[1].isEmpty() ? g[0] : g[0] + "  (" + g[1] + ")");
            glassesGroup.addView(rb);
            if (g[1].equalsIgnoreCase(cur)) glassesGroup.check(4000 + i);
        }
        glassesGroup.setOnCheckedChangeListener((gr, id) -> {
            String[] g = glassesList.get(id - 4000);
            core.selectGlasses(g[1], g[0]);
        });
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

    private void askBatteryExemption() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                answer.setText("이미 배터리 제한이 해제돼 있어요. 삼성 휴대폰은 설정 → 배터리 → 백그라운드 사용 제한 → '절전 예외 앱'에도 '안경 AI'를 추가해 주세요.");
                return;
            }
            android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

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
    private volatile boolean logPending;
    @Override public void onLog(String s) {
        if (logPending) return;
        logPending = true;
        logView.postDelayed(() -> { logPending = false; logView.setText(core.logText()); }, 400);
    }
    @Override public void onAnswer(String s) { runOnUiThread(() -> answer.setText(s)); }
    @Override public void onPhoto(byte[] jpeg) {
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        runOnUiThread(() -> photo.setImageBitmap(bmp));
    }
    @Override public void onBusy(boolean b) {
        runOnUiThread(() -> {
            boolean ir = core.interp.running;
            goBtn.setEnabled(!b); againBtn.setEnabled(!b); voiceBtn.setEnabled(!b);
            convoBtn.setEnabled(!b || ir); listenBtn.setEnabled(!b || ir);
            convoBtn.setText(ir && !core.interp.listenMode ? "■ 통역 끝내기" : "🗣 대화 통역");
            listenBtn.setText(ir && core.interp.listenMode ? "■ 통역 끝내기" : "📺 듣기 통역");
        });
    }
    @Override public void onHistoryChanged() { runOnUiThread(this::refreshHistory); }
}
