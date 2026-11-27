package com.jengburi.glassesai;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 화면: 아래 탭 4개 (홈 · 기능 · 기록 · 설정).
 *  - 홈: 상태, 큰 '그냥 물어보기', 자주 쓰는 버튼, AI 대답
 *  - 기능: 음식·재미·이동·기록 카드 (누르면 바로 실행)
 *  - 기록: 지난 대화/사진
 *  - 설정: 접었다 펴는 묶음
 */
public class MainActivity extends Activity implements AppCore.Ui {
    // ---- 색 ----
    static final int BG = 0xFF0F1115, CARD = 0xFF1A1D24, CARD2 = 0xFF252A35, LINE = 0xFF2E3440;
    static final int ACC = 0xFFFFB020, ACC_DARK = 0xFF2A2210, TXT = 0xFFF2F3F5, SUB = 0xFF9AA0AA;
    static final int GREEN = 0xFF3DDC84, RED = 0xFFFF6B6B, BLUE = 0xFF5B9BFF, PURPLE = 0xFFB18CFF;

    static final float[] RATES = {0.9f, 1.0f, 1.3f, 1.6f};
    static final String[] RATE_NAMES = {"천천히", "보통", "빠르게", "아주 빠르게"};
    static final float[] GAINS = {1f, 2f, 3f, 4f};

    private AppCore core;
    private EditText keyEdit, modelEdit, promptEdit, triggerEdit, playEdit, nextEdit, prevEdit, wakeEdit, uploadUrlEdit, uploadKeyEdit;
    private CompoundButton wakeBox, uploadBox, speakerBox, summaryBox, interpMicBox, liveBox, paidBox, suggestBox;
    private CompoundButton voiceBox, mediaBox, followBox, galleryBox, geminiEarBox, glassesMicBox, smartBox, smartFollowBox;
    private final List<CompoundButton> checks = new ArrayList<>();
    private Chips langChips, rateChips, gainChips, modeChips, mapChips, camChips;
    private RadioGroup glassesGroup;
    private final List<String[]> glassesList = new ArrayList<>();

    private View askBtn, photoBtn, convoBtn, listenBtn, meetBtn, againBtn;
    private TextView convoLabel, listenLabel, meetLabel, serviceBtn, statusConn, statusSub, kcalView;
    private ImageView photo;
    private TextView answer, logView, keyStatus, remoteStatus, signalView;
    private final TextView[] slotCode = new TextView[5], slotAct = new TextView[5];
    private LinearLayout historyList;
    private final ScrollView[] pages = new ScrollView[4];
    private final TextView[] tabs = new TextView[4];
    private int curTab = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        core = AppCore.get(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(CARD);
        buildUi();
        askPermissions();
    }

    @Override protected void onResume() {
        super.onResume();
        core.ui = this;
        loadFields();
        logView.setText(core.logText());
        answer.setText(core.lastAnswer == null || core.lastAnswer.isEmpty() ? "여기에 AI 대답이 나와요" : core.lastAnswer);
        if (core.lastJpeg != null) onPhoto(core.lastJpeg);
        onBusy(core.busy);
        updateServiceBtn();
        refreshHistory();
        refreshGlasses();
        if (core.key().isEmpty()) showTab(3);
    }

    @Override protected void onPause() {
        super.onPause();
        saveFields();
        core.ui = null;
    }

    // =====================================================================
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // 머리글
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(18), dp(20), dp(10));
        TextView title = text("쟁부리 AI 안경", 22, TXT, true);
        head.addView(title);
        TextView sub = text("AI 안경 컨트롤러", 13, SUB, false);
        head.addView(sub);
        root.addView(head);

        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        for (int i = 0; i < 4; i++) {
            ScrollView sv = new ScrollView(this);
            sv.setFillViewport(true);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(16), dp(4), dp(16), dp(24));
            sv.addView(col);
            pages[i] = sv;
            body.addView(sv);
        }
        buildHome(col(0));
        buildFeatures(col(1));
        buildHistory(col(2));
        buildSettings(col(3));

        // 아래 탭
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setBackgroundColor(CARD);
        tabBar.setPadding(0, dp(6), 0, dp(6));
        String[] names = {"🏠\n홈", "✨\n기능", "🕘\n기록", "⚙️\n설정"};
        for (int i = 0; i < 4; i++) {
            TextView t = text(names[i], 13, SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(6), 0, dp(6));
            final int idx = i;
            t.setOnClickListener(v -> showTab(idx));
            tabs[i] = t;
            tabBar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        root.addView(tabBar);
        setContentView(root);
        showTab(0);
        statusConn.post(tick);
    }

    private LinearLayout col(int i) { return (LinearLayout) pages[i].getChildAt(0); }

    private void showTab(int i) {
        if (curTab == 3 && i != 3) saveFields();
        curTab = i;
        for (int k = 0; k < 4; k++) {
            pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            tabs[k].setTextColor(k == i ? ACC : SUB);
        }
        if (i == 2) refreshHistory();
        if (i == 1) updateKcal();
    }

    // ============================ 홈 ============================
    private void buildHome(LinearLayout col) {
        LinearLayout st = card(col);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        statusConn = text("", 16, TXT, true);
        statusConn.setOnClickListener(v -> new Thread(core.ble::requestBattery).start());
        row.addView(statusConn, new LinearLayout.LayoutParams(0, -2, 1f));
        serviceBtn = pill("", ACC_DARK, ACC);
        serviceBtn.setOnClickListener(v -> toggleService());
        row.addView(serviceBtn);
        st.addView(row);
        statusSub = text("", 13, SUB, false);
        statusSub.setPadding(0, dp(6), 0, 0);
        st.addView(statusSub);

        // 큰 버튼
        askBtn = bigTile(col, "🎤", "그냥 물어보기", "말로 물어보면, 앞을 봐야 할 땐 알아서 찍어요", ACC, 0xFF1A1205);
        askBtn.setOnClickListener(v -> { saveFields(); core.smartAsk("화면 버튼"); });

        LinearLayout g1 = gridRow(col);
        photoBtn = tile(g1, "📸", "찍고 설명", null, BLUE);
        photoBtn.setOnClickListener(v -> { saveFields(); core.trigger("화면 버튼"); });
        convoBtn = tile(g1, "🗣", "대화 통역", null, GREEN);
        convoLabel = (TextView) ((LinearLayout) convoBtn).getChildAt(1);
        convoBtn.setOnClickListener(v -> { saveFields(); if (core.interp.running) core.interp.stop(); else core.interp.start(false); });
        LinearLayout g2 = gridRow(col);
        listenBtn = tile(g2, "📺", "듣기 통역", null, PURPLE);
        listenLabel = (TextView) ((LinearLayout) listenBtn).getChildAt(1);
        listenBtn.setOnClickListener(v -> { saveFields(); if (core.interp.running) core.interp.stop(); else core.interp.start(true); });
        meetBtn = tile(g2, "🎙", "회의 기록", null, RED);
        meetLabel = (TextView) ((LinearLayout) meetBtn).getChildAt(1);
        meetBtn.setOnClickListener(v -> core.feature("회의"));

        LinearLayout small = new LinearLayout(this);
        small.setPadding(0, dp(4), 0, dp(4));
        col.addView(small);
        againBtn = smallBtn(small, "🔁 다시 묻기");
        againBtn.setOnClickListener(v -> { saveFields(); core.askAgain(); });
        smallBtn(small, "🔊 다시 듣기").setOnClickListener(v -> core.replay());
        smallBtn(small, "⏹ 멈추기").setOnClickListener(v -> {
            core.stopSpeaking();
            if (core.features.meetingOn) core.features.stopMeeting();
            if (core.interp.running) core.interp.stop();
        });

        LinearLayout ac = card(col);
        ac.addView(text("AI 대답", 13, ACC, true));
        answer = text("", 17, TXT, false);
        answer.setPadding(0, dp(8), 0, dp(4));
        answer.setTextIsSelectable(true);
        answer.setLineSpacing(0, 1.2f);
        ac.addView(answer);
        photo = new ImageView(this);
        photo.setAdjustViewBounds(true);
        photo.setPadding(0, dp(8), 0, 0);
        ac.addView(photo, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout hc = card(col);
        hc.addView(text("💡 이렇게 불러 보세요", 13, ACC, true));
        hc.addView(hint("\"쟁부리야, 이거 몇 칼로리야?\"\n\"쟁부리야, 냉장고 보고 뭐 해 먹지?\"\n\"쟁부리야, 서울역 가는 길 알려 줘\"\n\"쟁부리야, 주차했어\" → \"내 차 어디 있어?\"\n\"쟁부리야, 영어 통역해 줘\""));
    }

    // ============================ 기능 ============================
    private void buildFeatures(LinearLayout col) {
        LinearLayout kc = card(col);
        LinearLayout kr = new LinearLayout(this);
        kr.setGravity(Gravity.CENTER_VERTICAL);
        kcalView = text("", 15, TXT, true);
        kr.addView(kcalView, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView reset = pill("초기화", CARD2, SUB);
        reset.setOnClickListener(v -> { core.features.resetKcal(); updateKcal(); });
        kr.addView(reset);
        kc.addView(kr);

        col.addView(groupTitle("🍽 음식"));
        LinearLayout r1 = gridRow(col);
        feat(r1, "🍔", "칼로리 경찰", "\"이거 몇 칼로리야?\"", ACC, () -> core.trigger("기능 카드", AppCore.M_KCAL));
        feat(r1, "🧑‍🍳", "냉장고 셰프", "\"이걸로 뭐 해 먹지?\"", GREEN, () -> core.trigger("기능 카드", AppCore.M_CHEF));

        col.addView(groupTitle("😎 재미"));
        LinearLayout r2 = gridRow(col);
        feat(r2, "💰", "가격 감별사", "\"이거 싼 거야?\"", ACC, () -> core.trigger("기능 카드", AppCore.M_PRICE));
        feat(r2, "🐶", "동물 속마음", "\"얘 지금 무슨 생각해?\"", PURPLE, () -> core.trigger("기능 카드", AppCore.M_PET));
        LinearLayout r3 = gridRow(col);
        feat(r3, "👗", "코디 평가", "\"오늘 옷 어때?\"", BLUE, () -> core.trigger("기능 카드", AppCore.M_STYLE));
        feat(r3, "🕵", "팩트 체크", "\"방금 그 말 사실이야?\"", RED, () -> core.feature("팩트"));
        LinearLayout r3b = gridRow(col);
        feat(r3b, "🔬", "AI 현미경", "\"이거 무슨 나무야?\" (내시경)", GREEN, () -> core.trigger("기능 카드", AppCore.M_MICRO));
        r3b.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        col.addView(groupTitle("🗺 이동"));
        LinearLayout r4 = gridRow(col);
        feat(r4, "🧭", "길안내", "\"서울역 가는 길\"", BLUE, () -> core.feature("길안내"));
        feat(r4, "📍", "내 위치", "\"여기 어디야?\"", GREEN, () -> core.feature("위치"));
        LinearLayout r5 = gridRow(col);
        feat(r5, "🅿️", "주차 기억", "\"쟁부리야, 주차\"", ACC, () -> core.feature("주차"));
        feat(r5, "🚗", "내 차 찾기", "\"내 차 어디 있어?\"", PURPLE, () -> core.feature("내차"));

        col.addView(groupTitle("📝 기록"));
        LinearLayout r6 = gridRow(col);
        feat(r6, "📔", "오늘 일기", "\"오늘 일기 써 줘\"", GREEN, () -> core.feature("일기"));
        feat(r6, "🎙", "회의 기록", "\"회의 기록 시작\"", RED, () -> core.feature("회의"));

        LinearLayout nc = card(col);
        nc.addView(hint("• 모든 기능은 \"쟁부리야\"로도 부를 수 있어요.\n"
                + "• 칼로리·가격은 사진을 보고 짐작하는 값이에요. 정확하지 않을 수 있어요.\n"
                + "• 회의 기록은 내가 참여한 대화에서만, 참석자에게 알리고 써 주세요.\n"
                + "• 길안내 음성은 네이버(구글) 지도가 안경 스피커로 들려줘요."));
    }

    private void feat(LinearLayout row, String emoji, String name, String ex, int color, Runnable r) {
        View t = tile(row, emoji, name, ex, color);
        t.setOnClickListener(v -> { saveFields(); showTab(0); r.run(); });
    }

    private void updateKcal() {
        if (kcalView == null) return;
        int c = core.features.kcalCount();
        kcalView.setText(c == 0 ? "🍽 오늘 먹은 음식: 아직 없음" : "🍽 오늘 " + c + "번 · " + String.format("%,d", core.features.kcalTotal()) + " kcal");
    }

    // ============================ 기록 ============================
    private void buildHistory(LinearLayout col) {
        col.addView(groupTitle("지난 기록 (누르면 홈에서 크게 보기)"));
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        col.addView(historyList);
    }

    private void refreshHistory() {
        if (historyList == null) return;
        historyList.removeAllViews();
        List<History.Entry> list = History.list(this, 40);
        if (list.isEmpty()) { historyList.addView(hint("아직 기록이 없어요")); return; }
        for (History.Entry e : list) {
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), dp(12), dp(12), dp(12));
            item.setBackground(press(CARD, 16));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            if (e.photo != null) {
                ImageView th = new ImageView(this);
                th.setScaleType(ImageView.ScaleType.CENTER_CROP);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 16;
                th.setImageBitmap(BitmapFactory.decodeFile(e.photo.getAbsolutePath(), o));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(56), dp(56));
                tp.rightMargin = dp(12);
                item.addView(th, tp);
            }
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(text(e.time + "  ·  " + e.mode, 12, ACC, true));
            String a = e.answer.length() > 70 ? e.answer.substring(0, 70) + "…" : e.answer;
            String q = e.question == null || e.question.isEmpty() ? "" : "Q. " + e.question + "\n";
            TextView body = text(q + a, 14, TXT, false);
            body.setPadding(0, dp(3), 0, 0);
            tx.addView(body);
            item.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
            item.setOnClickListener(v -> {
                answer.setText(e.answer);
                core.lastAnswer = e.answer;
                if (e.photo != null) {
                    photo.setImageBitmap(BitmapFactory.decodeFile(e.photo.getAbsolutePath()));
                    try { core.lastJpeg = java.nio.file.Files.readAllBytes(e.photo.toPath()); } catch (Exception ignored) { }
                } else photo.setImageDrawable(null);
                showTab(0);
                pages[0].post(() -> pages[0].smoothScrollTo(0, ((View) answer.getParent()).getTop()));
            });
            historyList.addView(item, lp);
        }
    }

    // ============================ 설정 ============================
    private void buildSettings(LinearLayout col) {
        // 1. 기본
        LinearLayout s1 = fold(col, "🔑 기본 설정 (API 키)", core.key().isEmpty());
        TextView paste = wideBtn(s1, "📋 설정 한꺼번에 붙여넣기 (복사해 둔 4줄)");
        paste.setOnClickListener(v -> pasteSettings());
        keyStatus = text("", 13, SUB, true);
        keyStatus.setPadding(0, dp(10), 0, 0);
        s1.addView(keyStatus);
        s1.addView(label("Gemini API 키"));
        keyEdit = edit(s1);
        keyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout kr = new LinearLayout(this);
        s1.addView(kr);
        smallBtn(kr, "👁 키 보기").setOnClickListener(v -> {
            boolean hidden = (keyEdit.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD;
            keyEdit.setInputType(InputType.TYPE_CLASS_TEXT | (hidden ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            keyEdit.setSelection(keyEdit.getText().length());
        });
        smallBtn(kr, "💾 저장").setOnClickListener(v -> { saveFields(); updateKeyStatus(); toast("저장했어요"); });
        smallBtn(kr, "✅ 키 테스트").setOnClickListener(v -> testKey());
        s1.addView(label("모델 이름 (쉼표로 여러 개, 앞에서부터 시도)"));
        modelEdit = edit(s1);
        paidBox = sw(s1, "💳 결제한 키 사용 중 (통역·대답 더 빠르게)", "paidKey", false);

        // 2. 안경
        LinearLayout s2 = fold(col, "👓 안경 · 카메라 연결", false);
        s2.addView(label("사진 찍는 카메라"));
        String[] cams = {"👓 안경 카메라", "🔌 USB 카메라 (내시경·웹캠)"};
        camChips = new Chips(s2, cams, 2, i -> core.prefs.edit().putString("camSource", i == 1 ? "USB" : "안경").apply());
        s2.addView(hint("USB 카메라: 휴대폰에 꽂고 아래 '시험 촬영'을 한 번 눌러 허용해 주세요. 버튼·호출어·블루투스 이어폰 버튼으로 찍을 수 있어요."));
        wideBtn(s2, "📷 USB 카메라 시험 촬영").setOnClickListener(v -> testUsbCam());
        s2.addView(label("안경"));
        s2.addView(hint("페어링된 S1Pro / M88 중에서 고르세요."));
        glassesGroup = new RadioGroup(this);
        s2.addView(glassesGroup);
        wideBtn(s2, "목록 새로고침").setOnClickListener(v -> refreshGlasses());
        wideBtn(s2, "🔋 화면 꺼져도 동작하게 (배터리 제한 해제)").setOnClickListener(v -> askBatteryExemption());
        wideBtn(s2, "🗺 화면 꺼져도 지도 바로 열기 (다른 앱 위에 표시 허용)").setOnClickListener(v -> askOverlay());

        // 3. 호출어
        LinearLayout s3 = fold(col, "🗣 말로 부르기 (호출어)", false);
        s3.addView(hint("휴대폰이 계속 듣다가 호출어가 들리면 AI가 깨어나요. \"쟁부리야, 오늘 날씨 어때?\"처럼 이어서 말해도 돼요. 안경 대기가 켜져 있어야 해요."));
        wakeBox = sw(s3, "호출어로 부르기 켜기", "wakeOn", false);
        wakeBox.setOnCheckedChangeListener((v, on) -> {
            core.prefs.edit().putBoolean("wakeOn", on).apply();
            if (GlassesService.running) { if (on) core.wakeWord.start(); else core.wakeWord.stop(); }
        });
        s3.addView(label("호출어"));
        wakeEdit = edit(s3);

        // 4. 대화·소리
        LinearLayout s4 = fold(col, "💬 대화 · 소리", false);
        smartBox = sw(s4, "안경 버튼 = 그냥 물어보기 (끄면 바로 찍고 설명)", "buttonSmart", true);
        smartFollowBox = sw(s4, "대답 뒤 이어서 계속 대화", "smartFollow", true);
        voiceBox = sw(s4, "'찍고 설명' 때도 말로 질문하기", "voice", false);
        followBox = sw(s4, "'찍고 설명' 뒤에도 이어서 대화", "followUp", false);
        geminiEarBox = sw(s4, "Gemini가 목소리를 직접 듣기 (추천)", "geminiListen", true);
        glassesMicBox = sw(s4, "안경 마이크 사용 (끄면 휴대폰 마이크)", "glassesMic", true);
        galleryBox = sw(s4, "찍은 사진을 갤러리에도 저장", "gallery", true);
        s4.addView(label("읽는 속도"));
        rateChips = new Chips(s4, RATE_NAMES, 4, i -> core.prefs.edit().putFloat("rate", RATES[i]).apply());
        s4.addView(label("'찍고 설명' 기본 모드"));
        modeChips = new Chips(s4, AppCore.MODE_NAMES, 3, i -> {
            core.prefs.edit().putInt("mode", i).apply();
            promptEdit.setVisibility(i == AppCore.MODE_NAMES.length - 1 ? View.VISIBLE : View.GONE);
        });
        promptEdit = edit(s4);
        promptEdit.setSingleLine(false);
        promptEdit.setMinLines(3);
        promptEdit.setGravity(Gravity.TOP);
        promptEdit.setHint("AI에게 할 말을 직접 적어 주세요");

        // 5. 통역
        LinearLayout s5 = fold(col, "🌐 통역", false);
        s5.addView(hint("말로도 시킬 수 있어요: \"영어 통역해 줘\", \"지금 뉴스야\", \"요약해서 통역해 줘\". 끝낼 땐 안경 버튼."));
        s5.addView(label("상대방 언어"));
        langChips = new Chips(s5, Interpreter.LANG_NAMES, 4, i -> core.prefs.edit().putString("interpLang", Interpreter.LANG_NAMES[i]).apply());
        suggestBox = sw(s5, "💡 대화 통역 때 '추천 대답'도 알려 주기", "interpSuggest", false);
        speakerBox = sw(s5, "내 말 번역은 휴대폰 스피커로 크게", "interpSpeaker", true);
        summaryBox = sw(s5, "듣기 통역은 요약해서", "interpSummary", false);
        liveBox = sw(s5, "듣기 통역 실시간 모드 (안 되면 자동으로 기존 방식)", "liveMode", true);
        interpMicBox = sw(s5, "통역 때 안경 마이크 사용 (TV·상대방 소리는 휴대폰이 더 좋아요)", "interpGlassesMic", false);
        s5.addView(label("마이크 소리 키우기"));
        String[] gn = {"1배", "2배", "3배", "4배"};
        gainChips = new Chips(s5, gn, 4, i -> core.prefs.edit().putFloat("micGain", GAINS[i]).apply());

        // 6. 지도
        LinearLayout s6 = fold(col, "🗺 길안내 지도", false);
        s6.addView(hint("네이버: 장소를 찾아 경로를 띄워요 (도보·대중교통은 '안내 시작'을 한 번 눌러 주세요, 자동차는 바로 시작).\n구글: 도보 안내가 말로 바로 시작돼요."));
        String[] maps = {"네이버 지도", "구글 지도"};
        mapChips = new Chips(s6, maps, 2, i -> core.prefs.edit().putString("mapApp", maps[i]).apply());

        // 7. 드라이브
        LinearLayout s7 = fold(col, "📤 드라이브 업로드 (블로그·쇼츠용)", false);
        s7.addView(hint("찍은 사진·AI 설명·일기·회의 요약을 드라이브 '안경 촬영/날짜' 폴더에 올려요."));
        uploadBox = sw(s7, "자동으로 드라이브에 올리기", "autoUpload", true);
        s7.addView(label("업로드 주소 (앱스 스크립트 웹 앱 URL)"));
        uploadUrlEdit = edit(s7);
        s7.addView(label("비밀 코드"));
        uploadKeyEdit = edit(s7);
        LinearLayout ur = new LinearLayout(this);
        s7.addView(ur);
        smallBtn(ur, "연결 테스트").setOnClickListener(v -> {
            saveFields();
            String url = uploadUrlEdit.getText().toString().trim(), key = uploadKeyEdit.getText().toString().trim();
            toast("업로드 테스트 중…");
            new Thread(() -> {
                String msg;
                try { Uploader.upload(url, key, "연결테스트.txt", null, null, "안경 AI 앱 연결 테스트 성공"); msg = "✅ 드라이브에 테스트 파일을 올렸어요!"; }
                catch (Exception e) { msg = "❌ " + e.getMessage(); }
                String m = msg;
                runOnUiThread(() -> toast(m));
            }).start();
        });
        smallBtn(ur, "안경 파일 전부 올리기").setOnClickListener(v -> { saveFields(); core.syncAllToDrive(); });

        // 7-1. 반지 리모컨
        LinearLayout sr = fold(col, "🎮 블루투스 리모컨 (반지)", false);
        sr.addView(hint("① 리모컨을 휴대폰과 블루투스로 연결하세요.\n② 아래 '접근성에서 켜기' → '쟁부리 리모컨'을 켜세요 (화면이 꺼져도 버튼을 받아요).\n③ 버튼마다 '배우기'를 누르고 리모컨 버튼을 한 번 누르세요.\n④ 오른쪽 동작을 눌러 원하는 기능을 고르세요."));
        remoteStatus = text("", 14, TXT, true);
        remoteStatus.setPadding(0, dp(8), 0, 0);
        sr.addView(remoteStatus);
        wideBtn(sr, "♿ 접근성에서 '쟁부리 리모컨' 켜기").setOnClickListener(v -> {
            try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); toast("설치된 앱 → '쟁부리 리모컨'을 켜 주세요"); }
            catch (Exception e) { toast("접근성 설정을 열지 못했어요"); }
        });
        sw(sr, "리모컨으로 앱 조작하기", "remoteOn", true).setOnCheckedChangeListener((v, on) -> {
            core.prefs.edit().putBoolean("remoteOn", on).apply();
            RemoteService r = RemoteService.inst; if (r != null && RemoteService.running) r.applyMouse();
        });
        CompoundButton mouseSw = sw(sr, "커서 방식 리모컨 받기 (BLE-M3 등, 안드로이드 14 이상)", "remoteMouse", true);
        mouseSw.setOnCheckedChangeListener((v, on) -> {
            core.prefs.edit().putBoolean("remoteMouse", on).apply();
            RemoteService r = RemoteService.inst; if (r != null && RemoteService.running) r.applyMouse();
        });
        sr.addView(hint("※ 켜 두면 리모컨 커서가 움직이지 않고 버튼으로 동작해요. 커서가 필요하면 끄세요."));
        signalView = text("📡 들어온 신호: (리모컨 버튼을 눌러 보세요)", 13, ACC, true);
        signalView.setPadding(0, dp(10), 0, dp(4));
        sr.addView(signalView);
        for (int i = 0; i < RemoteKeys.SLOTS.length; i++) {
            final int slot = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, 0);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(text(RemoteKeys.SLOTS[i], 15, TXT, true));
            slotCode[i] = text("", 12, SUB, false);
            info.addView(slotCode[i]);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView learn = pill("배우기", CARD2, TXT);
            learn.setOnClickListener(v -> {
                RemoteKeys.learnSlot = slot;
                slotCode[slot].setText("👉 지금 리모컨 버튼을 누르세요…");
                slotCode[slot].setTextColor(ACC);
            });
            row.addView(learn);
            slotAct[i] = pill("", ACC_DARK, ACC);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
            ap.leftMargin = dp(8);
            slotAct[i].setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                    .setTitle(RemoteKeys.SLOTS[slot] + " 버튼 동작")
                    .setItems(RemoteKeys.ACTIONS, (d, which) -> { RemoteKeys.setAct(core, slot, which); updateRemote(); })
                    .show());
            row.addView(slotAct[i], ap);
            sr.addView(row);
        }
        RemoteKeys.listener = (slot, code) -> runOnUiThread(() -> { updateRemote(); toast(RemoteKeys.SLOTS[slot] + " = " + RemoteKeys.codeName(code)); });

        // 8. 버튼 (고급)
        LinearLayout s8 = fold(col, "🎛 안경 버튼 동작 (고급)", false);
        s8.addView(hint("붙일 수 있는 동작: 그냥물어보기 / 사진 / 통역 / 듣기통역 / 다시듣기 / 칼로리 경찰 / 냉장고 셰프 / 가격 감별사 / 동물 속마음 / 코디 평가 / 주차 / 내차 / 위치 / 일기 / 팩트 / 회의 / 길안내\n바꾼 뒤에는 안경 대기를 껐다 켜 주세요."));
        s8.addView(label("안경 블루투스 신호 → 동작 (예: 97 또는 97:번역, 51:다시듣기)"));
        triggerEdit = edit(s8);
        mediaBox = sw(s8, "안경 터치(재생 신호)도 사용", "mediaKey", true);
        s8.addView(label("재생/일시정지 → (비우면 그냥 물어보기)"));
        playEdit = edit(s8);
        s8.addView(label("다음 곡 →"));
        nextEdit = edit(s8);
        s8.addView(label("이전 곡 →"));
        prevEdit = edit(s8);

        // 9. 진행 기록
        LinearLayout s9 = fold(col, "📜 진행 기록 (문제 생기면 캡처해서 보내 주세요)", false);
        logView = text("", 12, SUB, false);
        logView.setTextIsSelectable(true);
        s9.addView(logView);

        TextView ver = hint("버전 " + versionName());
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(16), 0, 0);
        col.addView(ver);
    }

    // =====================================================================
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            int b = core.ble.battery;
            String conn = core.ble.isConnected() ? (core.ble.jianyun ? "👓 M88" : "👓 S1Pro") : "👓 연결 안 됨";
            String bat = b >= 0 ? "   🔋 " + b + "%" + (core.ble.charging ? "⚡" : "") : "";
            statusConn.setText(conn + bat);
            statusConn.setTextColor(core.ble.isConnected() ? TXT : SUB);
            StringBuilder s = new StringBuilder();
            if (core.features.meetingOn) s.append("🎙 회의 기록 중  ");
            else if (core.interp.running) s.append(core.interp.listenMode ? "📺 듣기 통역 중  " : "🗣 대화 통역 중  ");
            else if (core.busy) s.append("⏳ 작업 중…  ");
            if ((core.interp.running || core.features.meetingOn) && core.micRms >= 0) s.append(AudioQ.meter(core.micRms));
            if (core.wakeWord.isRunning() && !core.busy) {
                s.append("👂 \"").append(core.prefs.getString("wakeWord", "쟁부리야")).append("\" 대기 중");
                if (!core.wakeWord.lastHeard.isEmpty()) s.append("\n들은 말: ").append(core.wakeWord.lastHeard);
            }
            if (s.length() == 0) s.append(GlassesService.running ? "안경 버튼을 누르거나 아래 버튼을 눌러 보세요" : "오른쪽 버튼으로 안경 대기를 켜 주세요");
            statusSub.setText(s.toString().trim());
            meetLabel.setText(core.features.meetingOn ? "회의 끝내기" : "회의 기록");
            if (signalView != null && !RemoteKeys.lastSignal.isEmpty() && RemoteService.running) signalView.setText("📡 들어온 신호: " + RemoteKeys.lastSignal);
            meetBtn.setEnabled(!core.busy || core.features.meetingOn);
            meetBtn.setAlpha(meetBtn.isEnabled() ? 1f : 0.4f);
            statusConn.postDelayed(this, 1000);
        }
    };

    private void toggleService() {
        saveFields();
        if (GlassesService.running) GlassesService.stop(this); else GlassesService.start(this);
        serviceBtn.postDelayed(this::updateServiceBtn, 500);
    }

    private void updateServiceBtn() {
        boolean on = GlassesService.running;
        serviceBtn.setText(on ? "● 대기 중" : "○ 대기 켜기");
        serviceBtn.setTextColor(on ? GREEN : ACC);
        serviceBtn.setBackground(round(on ? 0xFF12261A : ACC_DARK, 20));
    }

    private void loadFields() {
        keyEdit.setText(core.prefs.getString("key", ""));
        modelEdit.setText(core.models());
        uploadUrlEdit.setText(core.prefs.getString("uploadUrl", ""));
        uploadKeyEdit.setText(core.prefs.getString("uploadKey", ""));
        wakeEdit.setText(core.prefs.getString("wakeWord", "쟁부리야"));
        promptEdit.setText(core.prefs.getString("customPrompt", AppCore.MODE_PROMPTS[0]));
        triggerEdit.setText(core.triggerCodes());
        playEdit.setText(core.prefs.getString("actPlay", ""));
        nextEdit.setText(core.prefs.getString("actNext", "번역"));
        prevEdit.setText(core.prefs.getString("actPrev", "다시듣기"));
        for (CompoundButton c : checks) {
            Object[] t = (Object[]) c.getTag();
            c.setChecked(core.prefs.getBoolean((String) t[0], (Boolean) t[1]));
        }
        String lang = core.prefs.getString("interpLang", "자동");
        for (int i = 0; i < Interpreter.LANG_NAMES.length; i++) if (Interpreter.LANG_NAMES[i].equals(lang)) langChips.select(i, false);
        modeChips.select(core.mode(), false);
        promptEdit.setVisibility(core.mode() == AppCore.MODE_NAMES.length - 1 ? View.VISIBLE : View.GONE);
        float r = core.prefs.getFloat("rate", 1.0f);
        int ri = 1;
        for (int i = 0; i < RATES.length; i++) if (Math.abs(RATES[i] - r) < 0.01) ri = i;
        rateChips.select(ri, false);
        float g = core.prefs.getFloat("micGain", 3.0f);
        for (int i = 0; i < GAINS.length; i++) if (Math.abs(GAINS[i] - g) < 0.01) gainChips.select(i, false);
        mapChips.select(core.prefs.getString("mapApp", "네이버").contains("구글") ? 1 : 0, false);
        camChips.select(core.usbMode() ? 1 : 0, false);
        updateKcal();
        updateKeyStatus();
        updateRemote();
    }

    private void saveFields() {
        if (keyEdit == null) return;
        core.prefs.edit()
                .putString("key", keyEdit.getText().toString().replaceAll("\\s", ""))
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
            rb.setTextColor(TXT);
            rb.setButtonTintList(ColorStateList.valueOf(ACC));
            rb.setPadding(dp(4), dp(8), 0, dp(8));
            glassesGroup.addView(rb);
            if (g[1].equalsIgnoreCase(cur)) glassesGroup.check(4000 + i);
        }
        glassesGroup.setOnCheckedChangeListener((gr, id) -> {
            String[] g = glassesList.get(id - 4000);
            core.selectGlasses(g[1], g[0]);
        });
    }

    /** 클립보드의 "키=…, 모델=…, 업로드주소=…, 비밀코드=…" 를 한 번에 넣기 */
    private void pasteSettings() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            CharSequence clip = cm.hasPrimaryClip() ? cm.getPrimaryClip().getItemAt(0).coerceToText(this) : null;
            if (clip == null) { toast("복사된 글이 없어요. 4줄을 먼저 복사해 주세요."); return; }
            String all = clip.toString();
            int n = 0;
            StringBuilder got = new StringBuilder();
            android.content.SharedPreferences.Editor ed = core.prefs.edit();
            // 줄바꿈이 사라져 한 줄로 붙어 와도 되도록, 모양으로 각각 찾기
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(AIza[0-9A-Za-z_\\-]{20,}|AQ\\.[0-9A-Za-z_\\-\\.]{20,})").matcher(all);
            if (m.find()) { ed.putString("key", m.group(1)); n++; got.append("키 "); }
            m = java.util.regex.Pattern.compile("(https://script\\.google\\.com/macros/s/[0-9A-Za-z_\\-]+/exec)").matcher(all);
            if (m.find()) { ed.putString("uploadUrl", m.group(1)); n++; got.append("업로드주소 "); }
            m = java.util.regex.Pattern.compile("비밀\\s*(?:코드|번호)?\\s*[=:：]\\s*([0-9A-Za-z_\\-]+)").matcher(all);
            if (m.find()) { ed.putString("uploadKey", m.group(1)); n++; got.append("비밀코드 "); }
            m = java.util.regex.Pattern.compile("(gemini[0-9A-Za-z_\\-\\.]*)").matcher(all);
            StringBuilder models = new StringBuilder();
            while (m.find()) {
                String g = m.group(1).replaceAll("[\\.\\-]+$", "");
                if (models.indexOf(g) < 0) models.append(models.length() == 0 ? "" : ", ").append(g);
            }
            if (models.length() > 0) { ed.putString("model", models.toString()); n++; got.append("모델 "); }
            ed.apply();
            loadFields();
            toast(n > 0 ? "✅ " + n + "개 넣었어요: " + got.toString().trim() : "넣을 설정을 찾지 못했어요. 4줄을 다시 복사해 주세요.");
        } catch (Exception e) { toast("붙여넣기 실패: " + e.getMessage()); }
    }

    private void updateRemote() {
        if (remoteStatus == null) return;
        boolean on = RemoteService.running;
        remoteStatus.setText(on ? "🟢 리모컨 받기 켜짐 (화면 꺼져도 동작)" : "⚪ 접근성 꺼짐 — 앱 화면이 켜져 있을 때만 동작해요");
        remoteStatus.setTextColor(on ? GREEN : SUB);
        for (int i = 0; i < 5; i++) {
            if (RemoteKeys.learnSlot == i) continue;
            int c = core.prefs.getInt("rk_code_" + i, RemoteKeys.DEFAULT_CODES[i]);
            slotCode[i].setText("신호: " + RemoteKeys.codeName(c));
            slotCode[i].setTextColor(SUB);
            slotAct[i].setText(RemoteKeys.ACTIONS[RemoteKeys.act(core, i)].replace(" (AI 부르기)", ""));
        }
    }

    private void showSignal(String s) {
        if (signalView != null) runOnUiThread(() -> signalView.setText("📡 들어온 신호: " + s));
    }

    /** 터치(스와이프) 방식 리모컨: 블루투스 기기에서 온 터치만 방향으로 바꿈 */
    private float tsx, tsy;
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        android.view.InputDevice d = e.getDevice();
        boolean external = d != null && d.isExternal();
        if (!external) return super.dispatchTouchEvent(e);
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { tsx = e.getRawX(); tsy = e.getRawY(); return true; }
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
            float dx = e.getRawX() - tsx, dy = e.getRawY() - tsy;
            int code;
            if (Math.abs(dx) < dp(30) && Math.abs(dy) < dp(30)) code = RemoteKeys.TAP;
            else if (Math.abs(dx) > Math.abs(dy)) code = dx < 0 ? RemoteKeys.SWIPE_LEFT : RemoteKeys.SWIPE_RIGHT;
            else code = dy < 0 ? RemoteKeys.SWIPE_UP : RemoteKeys.SWIPE_DOWN;
            showSignal(RemoteKeys.codeName(code) + " · " + d.getName());
            RemoteKeys.onKey(this, code);
        }
        return true;
    }

    private MouseGesture mouseG;
    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        android.view.InputDevice d = e.getDevice();
        if (d != null && d.isExternal() && !RemoteService.running) {
            showSignal("커서 움직임 · " + d.getName());
            if (mouseG == null) mouseG = new MouseGesture(this);
            mouseG.feed(e);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    /** 접근성이 꺼져 있을 때: 앱 화면에서 리모컨 버튼 받기 */
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN)
            showSignal(RemoteKeys.codeName(e.getKeyCode()) + " (" + e.getKeyCode() + ")" + (e.getDevice() != null ? " · " + e.getDevice().getName() : ""));
        if (!RemoteService.running) {
            int code = e.getKeyCode();
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                if (RemoteKeys.onKey(this, code)) { pressed.add(code); return true; }
            } else if (e.getAction() == android.view.KeyEvent.ACTION_UP && pressed.remove(code)) return true;
            else if (pressed.contains(code)) return true;
        }
        return super.dispatchKeyEvent(e);
    }
    private final java.util.Set<Integer> pressed = new java.util.HashSet<>();

    private void testUsbCam() {
        if (core.busy) { toast("다른 작업 중이에요"); return; }
        if (!UsbCam.connected(this)) { toast("USB 카메라가 안 보여요. 꽂혀 있는지 확인해 주세요 (OTG 젠더 필요할 수 있어요)"); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 2);
            toast("카메라 권한을 허용한 뒤 다시 눌러 주세요");
            return;
        }
        toast("USB 카메라로 찍는 중…");
        new Thread(() -> {
            try {
                byte[] j = UsbCam.capture(this, core::log);
                core.lastJpeg = j;
                runOnUiThread(() -> { onPhoto(j); answer.setText("✅ USB 카메라 사진을 받았어요! 이제 '사진 찍는 카메라'를 USB로 고르면 돼요."); showTab(0); });
            } catch (Exception e) {
                String m = e.getMessage();
                runOnUiThread(() -> toast("❌ " + m));
                core.log("USB 카메라 실패: " + m);
            }
        }).start();
    }

    private void updateKeyStatus() {
        String k = core.key();
        if (k.isEmpty()) { keyStatus.setText("❌ 저장된 키가 없어요"); keyStatus.setTextColor(RED); return; }
        String shown = k.length() > 10 ? k.substring(0, 5) + "…" + k.substring(k.length() - 4) : k;
        keyStatus.setText("🔑 저장된 키: " + shown + "  (" + k.length() + "글자)");
        keyStatus.setTextColor(TXT);
    }

    /** 키를 저장하고 Gemini에 짧게 물어봐서 되는지 확인 */
    private void testKey() {
        // 붙여넣을 때 섞여 들어간 공백·줄바꿈·따옴표 제거
        String k = keyEdit.getText().toString().replaceAll("[\\s\"'`]", "");
        if (k.startsWith("키=")) k = k.substring(2);
        keyEdit.setText(k);
        saveFields();
        updateKeyStatus();
        if (k.isEmpty()) { toast("키를 먼저 넣어 주세요"); return; }
        keyStatus.setText("⏳ 키 확인 중…");
        final String key = k;
        new Thread(() -> {
            String msg; int color;
            try {
                String a = Gemini.askWithRetry(key, core.models(), "'연결 성공'이라고만 답해.", null, null, core::log);
                msg = "✅ 키가 잘 돼요! (" + (Gemini.lastFallbackModel != null ? Gemini.lastFallbackModel : core.models().split(",")[0].trim()) + ") " + a.trim();
                color = GREEN;
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                String why = m.contains("400") && (m.contains("API_KEY") || m.toLowerCase().contains("key")) ? "키가 틀렸거나 잘렸어요. AI 스튜디오에서 다시 복사해 주세요."
                        : m.contains("403") ? "이 키는 권한이 없어요. AI 스튜디오에서 'Generative Language API'가 켜진 키인지 확인해 주세요."
                        : m.contains("429") ? "키는 맞는데 사용 한도에 걸렸어요 (무료 한도이거나 결제 잔액 부족)."
                        : m.contains("404") ? "키는 맞는데 모델 이름이 없어요. 모델 칸을 gemini-3.8-flash 로 바꿔 보세요."
                        : "인터넷 연결이나 키를 확인해 주세요.";
                msg = "❌ " + why + "\n(" + (m.length() > 120 ? m.substring(0, 120) : m) + ")";
                color = RED;
            }
            String fm = msg; int fc = color;
            runOnUiThread(() -> { keyStatus.setText(fm); keyStatus.setTextColor(fc); });
        }).start();
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    // ============================ 꾸미기 도우미 ============================
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, int rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rDp));
        return g;
    }

    private android.graphics.drawable.Drawable press(int color, int rDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(color, rDp), round(0xFFFFFFFF, rDp));
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(s, 13, SUB, false);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(0, dp(6), 0, dp(4));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13, SUB, true);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private TextView groupTitle(String s) {
        TextView t = text(s, 15, TXT, true);
        t.setPadding(dp(4), dp(18), 0, dp(8));
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(round(CARD, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6); lp.bottomMargin = dp(6);
        parent.addView(c, lp);
        return c;
    }

    private TextView pill(String s, int bg, int fg) {
        TextView t = text(s, 13, fg, true);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        t.setBackground(round(bg, 20));
        return t;
    }

    private View bigTile(LinearLayout parent, String emoji, String title, String sub, int bg, int fg) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(20), dp(22), dp(20), dp(22));
        t.setBackground(press(bg, 24));
        TextView e = text(emoji, 34, fg, false);
        t.addView(e);
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(16), 0, 0, 0);
        tx.addView(text(title, 22, fg, true));
        TextView s = text(sub, 13, (fg & 0x00FFFFFF) | 0xB0000000, false);
        s.setPadding(0, dp(2), 0, 0);
        tx.addView(s);
        t.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8); lp.bottomMargin = dp(6);
        parent.addView(t, lp);
        return t;
    }

    private LinearLayout gridRow(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        parent.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    /** 카드형 버튼 (이모지 + 이름 + 예시) */
    private View tile(LinearLayout row, String emoji, String name, String ex, int color) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = round(CARD, 18);
        bg.setStroke(dp(1), (color & 0x00FFFFFF) | 0x55000000);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf((color & 0x00FFFFFF) | 0x44000000), bg, round(0xFFFFFFFF, 18)));
        t.addView(text(emoji, 26, TXT, false));
        TextView n = text(name, 16, TXT, true);
        n.setPadding(0, dp(6), 0, 0);
        t.addView(n);
        if (ex != null) {
            TextView e = text(ex, 12, color, false);
            e.setPadding(0, dp(3), 0, 0);
            t.addView(e);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(t, lp);
        return t;
    }

    private TextView smallBtn(LinearLayout row, String s) {
        TextView t = text(s, 14, TXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(12), dp(6), dp(12));
        t.setBackground(press(CARD2, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(t, lp);
        return t;
    }

    private TextView wideBtn(LinearLayout parent, String s) {
        TextView t = text(s, 14, TXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(13), dp(10), dp(13));
        t.setBackground(press(CARD2, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        parent.addView(t, lp);
        return t;
    }

    private EditText edit(LinearLayout parent) {
        EditText e = new EditText(this);
        e.setSingleLine();
        e.setTextColor(TXT);
        e.setHintTextColor(0xFF5C6370);
        e.setTextSize(15);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(round(CARD2, 12));
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private CompoundButton sw(LinearLayout parent, String s, String key, boolean def) {
        Switch c = new Switch(this);
        c.setText(s);
        c.setTextColor(TXT);
        c.setTextSize(14);
        c.setPadding(0, dp(10), 0, dp(10));
        c.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ACC, 0xFF8A8F99}));
        c.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{0x88FFB020, 0xFF3A3F4A}));
        c.setTag(new Object[]{key, def});
        c.setOnCheckedChangeListener((v, on) -> core.prefs.edit().putBoolean(key, on).apply());
        parent.addView(c, new LinearLayout.LayoutParams(-1, -2));
        checks.add(c);
        return c;
    }

    /** 접었다 펴는 설정 묶음. 안쪽 내용을 담을 레이아웃을 돌려줌. */
    private LinearLayout fold(LinearLayout parent, String title, boolean open) {
        LinearLayout c = card(parent);
        c.setPadding(0, 0, 0, 0);
        TextView h = text((open ? "▾  " : "▸  ") + title, 16, TXT, true);
        h.setPadding(dp(16), dp(16), dp(16), dp(16));
        h.setBackground(press(CARD, 18));
        c.addView(h, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setPadding(dp(16), 0, dp(16), dp(16));
        in.setVisibility(open ? View.VISIBLE : View.GONE);
        c.addView(in, new LinearLayout.LayoutParams(-1, -2));
        h.setOnClickListener(v -> {
            boolean show = in.getVisibility() != View.VISIBLE;
            in.setVisibility(show ? View.VISIBLE : View.GONE);
            h.setText((show ? "▾  " : "▸  ") + title);
            if (show && title.startsWith("📜")) logView.setText(core.logText());
        });
        return in;
    }

    interface OnPick { void pick(int i); }

    /** 칩 모양 단일 선택 */
    class Chips {
        final List<TextView> views = new ArrayList<>();
        final OnPick cb;
        Chips(LinearLayout parent, String[] items, int perRow, OnPick cb) {
            this.cb = cb;
            LinearLayout row = null;
            for (int i = 0; i < items.length; i++) {
                if (i % perRow == 0) { row = new LinearLayout(this_()); parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); }
                TextView t = text(items[i], 13, TXT, true);
                t.setGravity(Gravity.CENTER);
                t.setPadding(dp(4), dp(10), dp(4), dp(10));
                t.setMaxLines(1);
                final int idx = i;
                t.setOnClickListener(v -> select(idx, true));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                row.addView(t, lp);
                views.add(t);
            }
            if (items.length % perRow != 0) // 마지막 줄 칸 맞추기
                for (int k = items.length % perRow; k < perRow; k++) row.addView(new View(this_()), new LinearLayout.LayoutParams(0, 1, 1f));
            select(-1, false);
        }
        void select(int i, boolean fire) {
            for (int k = 0; k < views.size(); k++) {
                boolean on = k == i;
                views.get(k).setBackground(round(on ? ACC : CARD2, 12));
                views.get(k).setTextColor(on ? 0xFF1A1205 : TXT);
            }
            if (fire && i >= 0) cb.pick(i);
        }
    }

    private Activity this_() { return this; }

    // ============================ 권한 ============================
    private void askBatteryExemption() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("이미 해제돼 있어요. 삼성은 설정 → 배터리 → 백그라운드 사용 제한 → '절전 예외 앱'에도 추가해 주세요.");
                return;
            }
            android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void askOverlay() {
        if (android.provider.Settings.canDrawOverlays(this)) { toast("이미 허용돼 있어요"); return; }
        try {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
            toast("'쟁부리 AI 안경 컨트롤러'를 찾아 허용해 주세요");
        } catch (Exception e) { toast("설정 화면을 열지 못했어요"); }
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        need.add(Manifest.permission.RECORD_AUDIO);
        need.add(Manifest.permission.CAMERA);
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

    // ============================ AppCore.Ui ============================
    private volatile boolean logPending;
    @Override public void onLog(String s) {
        if (logPending) return;
        logPending = true;
        logView.postDelayed(() -> { logPending = false; if (logView.isShown()) logView.setText(core.logText()); }, 400);
    }
    @Override public void onAnswer(String s) { runOnUiThread(() -> answer.setText(s)); }
    @Override public void onPhoto(byte[] jpeg) {
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        runOnUiThread(() -> photo.setImageBitmap(bmp));
    }
    @Override public void onBusy(boolean b) {
        runOnUiThread(() -> {
            boolean ir = core.interp.running;
            for (View v : new View[]{askBtn, photoBtn, againBtn}) { v.setEnabled(!b); v.setAlpha(b ? 0.4f : 1f); }
            convoBtn.setEnabled(!b || ir); listenBtn.setEnabled(!b || ir);
            convoBtn.setAlpha(convoBtn.isEnabled() ? 1f : 0.4f); listenBtn.setAlpha(listenBtn.isEnabled() ? 1f : 0.4f);
            convoLabel.setText(ir && !core.interp.listenMode ? "통역 끝내기" : "대화 통역");
            listenLabel.setText(ir && core.interp.listenMode ? "통역 끝내기" : "듣기 통역");
            if (!b) updateKcal();
        });
    }
    @Override public void onHistoryChanged() { runOnUiThread(this::refreshHistory); }
}
