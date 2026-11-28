package com.jengburi.tutor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 라이브 과외: 웹캠 화면(1초에 한 장) + 마이크 소리를 Gemini Live 로 계속 보내고,
 * 선생님이 목소리로 바로 대답한다. 말을 끊고 끼어들 수도 있다.
 */
public class LiveTutor {
    static final String WS = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    public interface Ui { void onText(String who, String text); void onState(String s); }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Cam cam;
    private final LogSink log;
    private final Ui ui;
    public volatile boolean running;
    private volatile WebSocket ws;
    private volatile boolean ready, closed;
    private AudioTrack player;
    private boolean audioOut;
    private final StringBuilder teacher = new StringBuilder(), student = new StringBuilder();

    public LiveTutor(Context ctx, SharedPreferences prefs, Cam cam, LogSink log, Ui ui) {
        this.ctx = ctx; this.prefs = prefs; this.cam = cam; this.log = log; this.ui = ui;
    }

    public boolean isAudio() { return audioOut; }

    String key() { return prefs.getString("key", "").trim(); }

    /** Live 를 지원하는 모델 (목소리로 대답하는 native-audio 우선) */
    String findModel() {
        String saved = prefs.getString("liveModel", "");
        if (!saved.isEmpty()) return saved;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                    "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection();
            c.setRequestProperty("x-goog-api-key", key());
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            JSONArray arr = new JSONObject(bo.toString("UTF-8")).getJSONArray("models");
            String audio = null, text = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                String name = m.optString("name").replace("models/", "");
                if (!String.valueOf(m.optJSONArray("supportedGenerationMethods")).contains("bidiGenerateContent")) continue;
                if (!name.contains("gemini")) continue;
                if (name.contains("audio")) { if (audio == null || name.compareTo(audio) > 0) audio = name; }
                else if (text == null || name.compareTo(text) > 0) text = name;
            }
            String pick = audio != null ? audio : text;
            log.log("라이브 모델: " + pick);
            return pick;
        } catch (Exception e) {
            log.log("라이브 모델 찾기 실패: " + e.getMessage());
            return null;
        }
    }

    String system(String level, boolean giveAnswer) {
        return "너는 다정하고 실력 있는 1:1 과외 선생님 '쟁부리 선생님'이야. 학생 수준: " + level + ". 한국어로 말해.\n"
                + "학생 책상을 위에서 비추는 웹캠 화면이 1초마다 들어와 (문제지, 교과서, 손으로 쓴 풀이). 학생이 말하면 그 화면을 보고 대답해.\n"
                + (giveAnswer ? "물어보면 정답과 풀이를 알려 줘.\n"
                              : "정답을 바로 말하지 말고, 스스로 풀 수 있게 생각할 거리나 다음 한 걸음을 알려 줘. 학생이 '정답 알려줘'라고 분명히 말하면 알려 줘도 돼.\n")
                + "객관식 보기에 학생이 체크했다고 하면: 맞으면 칭찬, 틀리면 '아니야'라고 하고 그 보기가 왜 틀렸는지 설명한 뒤 한 번 더 생각해 보게 해. 두 번째도 틀리면 그때 정답 번호와 이유를 알려 줘.\n"
                + "대답은 1~3문장으로 짧게, 말하듯이. 풀이에서 실수가 보이면 어디인지 짚어 줘. 화면이 흐리거나 안 보이면 문제지를 웹캠 가까이 대 달라고 해.";
    }

    /** 끝날 때까지(또는 running=false) 라이브 과외. */
    @SuppressLint("MissingPermission")
    public void run(String level, boolean giveAnswer) {
        running = true;
        String model = findModel();
        if (model == null) { ui.onState("라이브 모델을 찾지 못했어요 (키를 확인해 주세요)"); running = false; return; }
        audioOut = model.contains("audio");
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int oldMode = am.getMode();
        am.setMode(AudioManager.MODE_IN_COMMUNICATION); // 에코 제거(선생님 목소리가 마이크로 다시 안 들어가게)
        am.setSpeakerphoneOn(true);
        AudioRecord rec = null;
        int reconnects = 0;
        try {
            if (audioOut) startPlayer();
            int rate = 16000, frame = rate / 10;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate * 2));
            rec.startRecording();
            short[] buf = new short[frame];
            while (running) {
                if (!connect(model, level, giveAnswer)) {
                    if (reconnects == 0) { prefs.edit().remove("liveModel").apply(); ui.onState("라이브 연결 실패 — 잠시 뒤 다시 눌러 주세요"); break; }
                    if (++reconnects > 5) { ui.onState("라이브 연결이 계속 끊겨요. 잠시 뒤 다시 시작해 주세요"); break; }
                    Thread.sleep(1500);
                    continue;
                }
                prefs.edit().putString("liveModel", model).apply();
                ui.onState(reconnects == 0 ? "🔴 라이브 과외 중 — 편하게 말 걸어 보세요" : "🔴 다시 연결됐어요");
                long lastFrame = 0;
                while (running && !closed) {
                    int n = rec.read(buf, 0, frame);
                    if (n <= 0) continue;
                    byte[] pcm = Mic.bytes(buf, n);
                    ws.send(new JSONObject().put("realtimeInput", new JSONObject().put("audio", new JSONObject()
                            .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000"))).toString());
                    long now = System.currentTimeMillis();
                    if (now - lastFrame >= 1000) { // 1초에 한 장
                        lastFrame = now;
                        byte[] jpg = cam.snapshot(60);
                        if (jpg != null) ws.send(new JSONObject().put("realtimeInput", new JSONObject().put("video", new JSONObject()
                                .put("data", Base64.encodeToString(jpg, Base64.NO_WRAP)).put("mimeType", "image/jpeg"))).toString());
                    }
                }
                try { ws.close(1000, "bye"); } catch (Exception ignored) { }
                if (!running) break;
                reconnects++;
                log.log("라이브 연결이 끊겨서 다시 연결할게요 (" + reconnects + ")");
                if (reconnects > 5) { ui.onState("라이브 연결이 계속 끊겨요. 잠시 뒤 다시 시작해 주세요"); break; }
            }
        } catch (Exception e) {
            log.log("라이브 오류: " + e.getMessage());
            ui.onState("라이브 오류: " + e.getMessage());
        } finally {
            running = false;
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
            try { if (ws != null) ws.close(1000, "bye"); } catch (Exception ignored) { }
            if (player != null) { try { player.stop(); player.release(); } catch (Exception ignored) { } player = null; }
            am.setSpeakerphoneOn(false);
            am.setMode(oldMode);
        }
    }

    private boolean connect(String model, String level, boolean giveAnswer) throws InterruptedException {
        ready = false; closed = false;
        CountDownLatch setup = new CountDownLatch(1);
        final String[] err = {null};
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
        Request req = new Request.Builder().url(WS).header("x-goog-api-key", key()).build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) {
                try {
                    JSONObject s = new JSONObject()
                            .put("model", "models/" + model)
                            .put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put(audioOut ? "AUDIO" : "TEXT")))
                            .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system(level, giveAnswer)))))
                            .put("inputAudioTranscription", new JSONObject())
                            .put("contextWindowCompression", new JSONObject().put("slidingWindow", new JSONObject()));
                    if (audioOut) s.put("outputAudioTranscription", new JSONObject());
                    w.send(new JSONObject().put("setup", s).toString());
                } catch (Exception e) { err[0] = e.getMessage(); setup.countDown(); }
            }
            @Override public void onMessage(WebSocket w, String text) { handle(text, setup); }
            @Override public void onMessage(WebSocket w, ByteString bytes) { handle(bytes.utf8(), setup); }
            @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                err[0] = t.getMessage() + (r != null ? " (" + r.code() + ")" : "");
                closed = true; setup.countDown();
            }
            @Override public void onClosed(WebSocket w, int code, String reason) {
                if (!ready) err[0] = "연결 닫힘 " + code + " " + reason;
                closed = true; setup.countDown();
            }
        });
        setup.await(15, TimeUnit.SECONDS);
        if (!ready) { log.log("라이브 연결 실패: " + err[0]); try { ws.cancel(); } catch (Exception ignored) { } return false; }
        log.log("라이브 연결됨 (" + model + ")");
        return true;
    }

    private void handle(String text, CountDownLatch setup) {
        try {
            JSONObject j = new JSONObject(text);
            if (j.has("setupComplete")) { ready = true; setup.countDown(); return; }
            if (j.has("error")) { log.log("라이브 오류: " + j.optJSONObject("error")); return; }
            if (j.has("goAway")) { log.log("라이브 서버가 곧 연결을 끊는대요 → 자동으로 다시 연결해요"); return; }
            JSONObject sc = j.optJSONObject("serverContent");
            if (sc == null) return;
            if (sc.optBoolean("interrupted") && player != null) { // 학생이 말을 끊으면 선생님 목소리 멈춤
                try { player.pause(); player.flush(); player.play(); } catch (Exception ignored) { }
            }
            JSONObject in = sc.optJSONObject("inputTranscription");
            if (in != null) { student.append(in.optString("text")); }
            JSONObject turn = sc.optJSONObject("modelTurn");
            if (turn != null) {
                flushStudent();
                JSONArray parts = turn.optJSONArray("parts");
                if (parts != null) for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.getJSONObject(i);
                    String t = p.optString("text", "");
                    if (!t.isEmpty() && !audioOut) teacher.append(t);
                    JSONObject inline = p.optJSONObject("inlineData");
                    if (inline != null && player != null) {
                        byte[] pcm = Base64.decode(inline.optString("data"), Base64.DEFAULT);
                        player.write(pcm, 0, pcm.length);
                    }
                }
            }
            JSONObject out = sc.optJSONObject("outputTranscription");
            if (out != null) teacher.append(out.optString("text"));
            if (sc.optBoolean("turnComplete") || sc.optBoolean("generationComplete")) {
                flushStudent();
                String t = teacher.toString().trim();
                teacher.setLength(0);
                if (!t.isEmpty()) ui.onText("teacher", t);
            }
        } catch (Exception ignored) { }
    }

    private void flushStudent() {
        String s = student.toString().trim();
        student.setLength(0);
        if (!s.isEmpty()) ui.onText("student", s);
    }

    private void startPlayer() {
        int rate = 24000;
        player = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(rate * 2 * 2).setTransferMode(AudioTrack.MODE_STREAM).build();
        player.play();
    }
}
