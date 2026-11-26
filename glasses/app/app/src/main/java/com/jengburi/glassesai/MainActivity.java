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
    private AppCore core;
    private EditText keyEdit, modelEdit, promptEdit, triggerEdit;
    private Button goBtn, againBtn, serviceBtn;
    private CheckBox voiceBox, mediaBox;
    private RadioGroup modeGroup;
    private ImageView photo;
    private TextView answer, logView;

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
    }

    @Override protected void onPause() {
        super.onPause();
        saveFields();
        core.ui = null;
    }

    private void buildUi() {
        int pad = dp(16);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col);

        TextView title = new TextView(this);
        title.setText("안경 AI (S1Pro + Gemini)");
        title.setTextSize(22);
        col.addView(title);

        goBtn = new Button(this);
        goBtn.setText("📸  안경으로 찍고 설명 듣기");
        goBtn.setTextSize(20);
        goBtn.setMinHeight(dp(96));
        goBtn.setOnClickListener(v -> { saveFields(); core.trigger("화면 버튼"); });
        col.addView(goBtn, new LinearLayout.LayoutParams(-1, -2));

        againBtn = new Button(this);
        againBtn.setText("🔁 방금 사진으로 다시 물어보기");
        againBtn.setOnClickListener(v -> { saveFields(); core.askAgain(); });
        col.addView(againBtn, new LinearLayout.LayoutParams(-1, -2));

        Button stopBtn = new Button(this);
        stopBtn.setText("🔇 읽기 멈추기");
        stopBtn.setOnClickListener(v -> core.stopSpeaking());
        col.addView(stopBtn, new LinearLayout.LayoutParams(-1, -2));

        answer = new TextView(this);
        answer.setTextSize(18);
        answer.setPadding(0, dp(12), 0, dp(12));
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

        voiceBox = new CheckBox(this);
        voiceBox.setText("🎤 찍은 뒤 말로 질문하기 (안경 마이크)");
        voiceBox.setOnCheckedChangeListener((v, c) -> core.prefs.edit().putBoolean("voice", c).apply());
        col.addView(voiceBox);

        // --- 안경 버튼 ---
        col.addView(section("안경 버튼으로 실행"));
        serviceBtn = new Button(this);
        serviceBtn.setOnClickListener(v -> {
            saveFields();
            if (GlassesService.running) GlassesService.stop(this); else GlassesService.start(this);
            serviceBtn.postDelayed(this::updateServiceBtn, 500);
        });
        col.addView(serviceBtn, new LinearLayout.LayoutParams(-1, -2));
        mediaBox = new CheckBox(this);
        mediaBox.setText("안경 터치(재생/일시정지 신호)로도 실행");
        mediaBox.setOnCheckedChangeListener((v, c) -> core.prefs.edit().putBoolean("mediaKey", c).apply());
        col.addView(mediaBox);
        col.addView(label("실행할 안경 신호 번호 (아래 기록의 '안경 신호: XX' 값, 여러 개는 쉼표)"));
        triggerEdit = new EditText(this);
        triggerEdit.setSingleLine();
        col.addView(triggerEdit);

        // --- 설정 ---
        col.addView(section("설정"));
        col.addView(label("Gemini API 키"));
        keyEdit = new EditText(this);
        keyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(keyEdit);
        col.addView(label("모델 이름 (쉼표로 여러 개, 앞에서부터 시도)"));
        modelEdit = new EditText(this);
        modelEdit.setSingleLine();
        col.addView(modelEdit);

        col.addView(section("진행 기록"));
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextColor(Color.GRAY);
        col.addView(logView);

        setContentView(sv);
    }

    private void loadFields() {
        keyEdit.setText(core.prefs.getString("key", ""));
        modelEdit.setText(core.models());
        promptEdit.setText(core.prefs.getString("customPrompt", AppCore.MODE_PROMPTS[0]));
        triggerEdit.setText(core.triggerCodes());
        voiceBox.setChecked(core.voiceQuestion());
        mediaBox.setChecked(core.prefs.getBoolean("mediaKey", true));
        modeGroup.check(1000 + core.mode());
        promptEdit.setVisibility(core.mode() == AppCore.MODE_NAMES.length - 1 ? View.VISIBLE : View.GONE);
    }

    private void saveFields() {
        core.prefs.edit()
                .putString("key", keyEdit.getText().toString().trim())
                .putString("model", modelEdit.getText().toString().trim())
                .putString("customPrompt", promptEdit.getText().toString().trim())
                .putString("triggers", triggerEdit.getText().toString().trim())
                .apply();
    }

    private void updateServiceBtn() {
        serviceBtn.setText(GlassesService.running ? "🟢 안경 버튼 대기 중 (누르면 끄기)" : "⚪ 안경 버튼 대기 켜기");
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
        runOnUiThread(() -> { goBtn.setEnabled(!b); againBtn.setEnabled(!b); });
    }
}
