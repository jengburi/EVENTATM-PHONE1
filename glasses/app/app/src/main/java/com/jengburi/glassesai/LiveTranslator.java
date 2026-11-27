package com.jengburi.glassesai;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Gemini Live API 로 실시간 통역: 마이크 소리를 끊김 없이 보내고, 짧게 쉴 때마다 한국어 번역을 받는다.
 * 실패하면 false 를 돌려서 기존(조각) 방식으로 넘어가게 한다.
 */
public class LiveTranslator {
    static final String WS = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    private final AppCore core;
    private WebSocket ws;
    private volatile boolean ready, closed;
    private AudioTrack player;
    private final StringBuilder turnText = new StringBuilder();
    final StringBuilder all = new StringBuilder();

    LiveTranslator(AppCore core) { this.core = core; }

    /** Live 를 지원하는 모델 이름 찾기 (설정값 우선). */
    String findModel() {
        String saved = core.prefs.getString("liveModel", "");
        if (!saved.isEmpty()) return saved;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                    "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection();
            c.setRequestProperty("x-goog-api-key", core.key());
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            JSONArray arr = new JSONObject(bo.toString("UTF-8")).getJSONArray("models");
            String best = null, bestAudio = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                String name = m.optString("name").replace("models/", "");
                if (!String.valueOf(m.optJSONArray("supportedGenerationMethods")).contains("bidiGenerateContent")) continue;
                if (name.contains("native-audio") || name.contains("audio")) { if (bestAudio == null || name.compareTo(bestAudio) > 0) bestAudio = name; }
                else if (best == null || name.compareTo(best) > 0) best = name;
            }
            String pick = best != null ? best : bestAudio;
            core.log("실시간 모델: " + pick);
            return pick;
        } catch (Exception e) {
            core.log("실시간 모델 찾기 실패: " + e.getMessage());
            return null;
        }
    }

    /** running 이 true 인 동안 실시간 통역. 연결 자체가 안 되면 false. */
    @SuppressLint("MissingPermission")
    public boolean run(AudioQ.Running running, boolean summary, boolean glassesMic) {
        String model = findModel();
        if (model == null) return false;
        boolean audioOut = model.contains("audio");
        CountDownLatch setup = new CountDownLatch(1);
        final String[] err = {null};
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
        Request req = new Request.Builder().url(WS).header("x-goog-api-key", core.key()).build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) {
                try {
                    String sys = "너는 동시통역사야. 들리는 말(뉴스, 강의, 방송, 대화)을 자연스러운 한국어로 바로바로 번역해. "
                            + (summary ? "핵심만 아주 짧게 전해. " : "빠짐없이 짧은 문장으로 번역해. ")
                            + "원래 한국어면 아무 말도 하지 마. 설명, 인사, 따옴표 없이 번역문만 말해. 말소리가 없으면 아무것도 하지 마.";
                    JSONObject setupMsg = new JSONObject().put("setup", new JSONObject()
                            .put("model", "models/" + model)
                            .put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put(audioOut ? "AUDIO" : "TEXT")))
                            .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys))))
                            .put("realtimeInputConfig", new JSONObject()
                                    .put("activityHandling", "NO_INTERRUPTION")
                                    .put("automaticActivityDetection", new JSONObject()
                                            .put("silenceDurationMs", 300).put("prefixPaddingMs", 100))));
                    w.send(setupMsg.toString());
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
        try { setup.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        if (!ready) {
            core.log("실시간 연결 실패: " + err[0] + " → 기존 방식으로 통역");
            try { ws.cancel(); } catch (Exception ignored) { }
            return false;
        }
        core.prefs.edit().putString("liveModel", model).apply();
        core.log("실시간 통역 연결됨 (" + model + ")");
        if (audioOut) startPlayer();

        core.wakeWord.pauseNow();
        AudioManager am = (AudioManager) core.ctx.getSystemService(Context.AUDIO_SERVICE);
        boolean sco = glassesMic && AudioQ.useGlassesMic(am, core::log);
        AudioRecord rec = null;
        try {
            int rate = 16000, frame = rate / 10;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(sco ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.MIC,
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate * 2));
            rec.startRecording();
            short[] buf = new short[frame];
            float g = AudioQ.gain(core.ctx);
            int cnt = 0; double msum = 0;
            while (running.get() && !closed) {
                int n = rec.read(buf, 0, frame);
                if (n <= 0) continue;
                AudioQ.applyGain(buf, n, g);
                double sq = 0; for (int i = 0; i < n; i++) sq += (double) buf[i] * buf[i];
                msum += Math.sqrt(sq / n);
                if (++cnt % 20 == 0) { core.log(AudioQ.meter(msum / 20)); msum = 0; }
                byte[] pcm = AudioQ.toBytes(buf, n);
                JSONObject msg = new JSONObject().put("realtimeInput", new JSONObject()
                        .put("audio", new JSONObject()
                                .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                                .put("mimeType", "audio/pcm;rate=16000")));
                ws.send(msg.toString());
            }
            if (closed && running.get()) core.log("실시간 연결이 끊겼어요 (세션 시간 제한일 수 있어요)");
        } catch (Exception e) {
            core.log("실시간 통역 오류: " + e.getMessage());
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
            if (sco) AudioQ.releaseGlassesMic(am);
            try { ws.close(1000, "bye"); } catch (Exception ignored) { }
            if (player != null) { try { player.stop(); player.release(); } catch (Exception ignored) { } player = null; }
        }
        return true;
    }

    private void handle(String text, CountDownLatch setup) {
        try {
            JSONObject j = new JSONObject(text);
            if (j.has("setupComplete")) { ready = true; setup.countDown(); return; }
            JSONObject sc = j.optJSONObject("serverContent");
            if (sc == null) return;
            JSONObject turn = sc.optJSONObject("modelTurn");
            if (turn != null) {
                JSONArray parts = turn.optJSONArray("parts");
                if (parts != null) for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.getJSONObject(i);
                    String t = p.optString("text", "");
                    if (!t.isEmpty()) { turnText.append(t); flushSentences(false); }
                    JSONObject inline = p.optJSONObject("inlineData");
                    if (inline != null && player != null) {
                        byte[] pcm = Base64.decode(inline.optString("data"), Base64.DEFAULT);
                        player.write(pcm, 0, pcm.length);
                    }
                }
            }
            JSONObject outTr = sc.optJSONObject("outputTranscription");
            if (outTr != null) addShown(outTr.optString("text"));
            if (sc.optBoolean("turnComplete")) flushSentences(true);
        } catch (Exception ignored) { }
    }

    /** 문장이 끝날 때마다 바로 읽기 */
    private synchronized void flushSentences(boolean end) {
        String t = turnText.toString();
        int idx = -1;
        for (int i = 0; i < t.length(); i++) { char c = t.charAt(i); if (c == '.' || c == '?' || c == '!' || c == '\n') idx = i; }
        if (end) idx = t.length() - 1;
        if (idx < 0) return;
        String sent = t.substring(0, idx + 1).trim();
        turnText.delete(0, idx + 1);
        if (sent.isEmpty()) return;
        addShown(sent);
        // 밀리면 빠르게 읽기
        core.speakQueuedFast(sent, Locale.KOREAN, core.isSpeaking());
    }

    private void addShown(String s) {
        if (s == null || s.trim().isEmpty()) return;
        all.append(' ').append(s.trim());
        String shown = all.length() > 800 ? "…" + all.substring(all.length() - 800) : all.toString().trim();
        core.answer(shown);
    }

    private void startPlayer() {
        int rate = 24000;
        player = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(rate * 2).setTransferMode(AudioTrack.MODE_STREAM).build();
        player.play();
    }
}
