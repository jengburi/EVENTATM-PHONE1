package com.jengburi.glassesai;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    static final String DEFAULT_PROMPT =
            "이 사진은 내가 쓴 안경 카메라로 방금 찍은 거야. 한국어로 짧게 답해 줘.\n"
            + "- 메뉴판, 표지판 같은 글자가 있으면 한국어로 번역해서 읽어 줘.\n"
            + "- 시험 문제가 있으면 정답과 짧은 풀이를 알려 줘.\n"
            + "- 그 외에는 앞에 보이는 것을 2~3문장으로 설명해 줘.\n"
            + "귀로 듣는 답이니 기호, 표, 마크다운 없이 말하듯이 써 줘.";

    private SharedPreferences prefs;
    private EditText keyEdit, modelEdit, promptEdit;
    private Button goBtn;
    private ImageView photo;
    private TextView answer, logView;
    private TextToSpeech tts;
    private GlassesBle ble;
    private GlassesWifi wifi;
    private volatile boolean busy;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("glassesai", MODE_PRIVATE);
        buildUi();
        ble = new GlassesBle(this, this::log);
        wifi = new GlassesWifi(this, this::log);
        tts = new TextToSpeech(this, s -> { if (s == TextToSpeech.SUCCESS) tts.setLanguage(Locale.KOREAN); });
        askPermissions();
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
        goBtn.setOnClickListener(v -> start());
        col.addView(goBtn, new LinearLayout.LayoutParams(-1, -2));

        answer = new TextView(this);
        answer.setTextSize(18);
        answer.setPadding(0, dp(12), 0, dp(12));
        col.addView(answer);

        photo = new ImageView(this);
        photo.setAdjustViewBounds(true);
        col.addView(photo, new LinearLayout.LayoutParams(-1, -2));

        col.addView(label("Gemini API 키 (aistudio.google.com 에서 무료 발급)"));
        keyEdit = new EditText(this);
        keyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyEdit.setText(prefs.getString("key", ""));
        col.addView(keyEdit);

        col.addView(label("모델 이름"));
        modelEdit = new EditText(this);
        modelEdit.setSingleLine();
        modelEdit.setText(prefs.getString("model", "gemini-3.8-flash"));
        col.addView(modelEdit);

        col.addView(label("AI에게 할 말 (기본 질문)"));
        promptEdit = new EditText(this);
        promptEdit.setMinLines(4);
        promptEdit.setGravity(Gravity.TOP);
        promptEdit.setText(prefs.getString("prompt", DEFAULT_PROMPT));
        col.addView(promptEdit);

        Button stopBtn = new Button(this);
        stopBtn.setText("읽기 멈추기");
        stopBtn.setOnClickListener(v -> tts.stop());
        col.addView(stopBtn);

        col.addView(label("진행 기록"));
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextColor(Color.GRAY);
        col.addView(logView);

        setContentView(sv);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(14), 0, 0);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void log(String s) {
        runOnUiThread(() -> logView.setText(s + "\n" + logView.getText()));
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
            need.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        List<String> missing = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
    }

    private void say(String s) {
        runOnUiThread(() -> tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "a"));
    }

    private void start() {
        if (busy) return;
        String key = keyEdit.getText().toString().trim();
        String model = modelEdit.getText().toString().trim();
        String prompt = promptEdit.getText().toString().trim();
        prefs.edit().putString("key", key).putString("model", model).putString("prompt", prompt).apply();
        if (key.isEmpty()) { answer.setText("먼저 아래에 Gemini API 키를 넣어 주세요."); return; }
        busy = true;
        goBtn.setEnabled(false);
        answer.setText("진행 중…");
        new Thread(() -> run(key, model, prompt)).start();
    }

    private void run(String key, String model, String prompt) {
        byte[] jpeg = null;
        try {
            ble.connect();
            say("찍을게요");
            ble.takePhoto();
            Thread.sleep(1500);
            if (!locationOn()) throw new Exception("휴대폰 위치(GPS)를 켜 주세요. 꺼져 있으면 안경 와이파이를 못 찾아요");
            String ssid = ble.wifiOn();
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
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            runOnUiThread(() -> photo.setImageBitmap(bmp));

            log("인터넷 연결 기다리는 중…");
            if (!wifi.waitForInternet(25)) log("인터넷 확인이 안 됐지만 시도해 볼게요");
            say("분석 중이에요");
            log("Gemini에게 물어보는 중…");
            String a = Gemini.ask(key, model, prompt, jpeg);
            runOnUiThread(() -> answer.setText(a));
            log("답변 받음");
            say(a);
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.toString() : e.getMessage();
            log("오류: " + m);
            runOnUiThread(() -> answer.setText("오류: " + m));
            say("오류가 났어요. " + m);
        } finally {
            busy = false;
            runOnUiThread(() -> goBtn.setEnabled(true));
        }
    }

    private boolean locationOn() {
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        return lm != null && lm.isLocationEnabled();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ble.close();
        wifi.disconnect();
        if (tts != null) tts.shutdown();
    }
}
