package com.jengburi.glassesai;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Google Gemini API 로 사진 + 질문을 보내고 답을 받는다. */
public class Gemini {
    /** 자동으로 찾아 성공한 모델 이름 (없으면 null). */
    public static volatile String lastFallbackModel;

    /** 503/429(혼잡)이면 잠시 쉬고 재시도, 모델 칸에 쉼표로 여러 개 적으면 차례로 시도. */
    public static String askWithRetry(String apiKey, String models, String prompt, byte[] jpeg, GlassesBle.Log log) throws Exception {
        return askWithRetry(apiKey, models, prompt, jpeg, null, log);
    }

    public static String askWithRetry(String apiKey, String models, String prompt, byte[] jpeg, byte[] wav, GlassesBle.Log log) throws Exception {
        Exception last = null;
        for (String model : models.split(",")) {
            model = model.trim();
            if (model.isEmpty()) continue;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    return ask(apiKey, model, prompt, jpeg, wav);
                } catch (Exception e) {
                    last = e;
                    String m = String.valueOf(e.getMessage());
                    boolean busy = m.contains(" 503") || m.contains(" 429") || m.contains(" 500");
                    if (!busy) break;
                    log.log(model + " 혼잡 → " + (attempt * 3) + "초 후 다시 시도 (" + attempt + "/3)");
                    Thread.sleep(attempt * 3000L);
                }
            }
        }
        // 적어 둔 모델이 모두 혼잡/없음이면, 내 키로 쓸 수 있는 flash 계열 모델을 찾아 차례로 시도
        try {
            java.util.List<String> avail = listFlashModels(apiKey);
            log.log("쓸 수 있는 모델: " + avail);
            int tried = 0;
            for (String m : avail) {
                if (models.contains(m)) continue;
                if (++tried > 5) break;
                try {
                    log.log(m + " 로 시도");
                    String a = ask(apiKey, m, prompt, jpeg, wav);
                    log.log("성공한 모델: " + m + " (다음부터 이 모델을 먼저 사용)");
                    lastFallbackModel = m;
                    return a;
                } catch (Exception e) { last = e; log.log(m + " 실패: " + e.getMessage()); }
            }
        } catch (Exception e) { log.log("모델 목록 조회 실패: " + e.getMessage()); }
        throw last != null ? last : new Exception("모델 이름이 비어 있어요");
    }

    /** generateContent 를 지원하는 flash 계열 모델 이름 목록 (최신 우선). */
    public static java.util.List<String> listFlashModels(String apiKey) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection();
        c.setRequestProperty("x-goog-api-key", apiKey);
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
        JSONArray arr = new JSONObject(bo.toString("UTF-8")).getJSONArray("models");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.getJSONObject(i);
            String name = m.optString("name").replace("models/", "");
            String methods = String.valueOf(m.optJSONArray("supportedGenerationMethods"));
            if (name.contains("flash") && methods.contains("generateContent")
                    && !name.contains("tts") && !name.contains("image") && !name.contains("audio")
                    && !name.contains("live") && !name.contains("embedding")) out.add(name);
        }
        java.util.Collections.sort(out, java.util.Collections.reverseOrder());
        return out;
    }

    public static String ask(String apiKey, String model, String prompt, byte[] jpeg) throws Exception {
        return ask(apiKey, model, prompt, jpeg, null);
    }

    public static String ask(String apiKey, String model, String prompt, byte[] jpeg, byte[] wav) throws Exception {
        JSONArray parts0 = new JSONArray().put(new JSONObject().put("text", prompt));
        if (jpeg != null) parts0.put(new JSONObject().put("inline_data", new JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))));
        if (wav != null) parts0.put(new JSONObject().put("inline_data", new JSONObject()
                .put("mime_type", "audio/wav")
                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))));
        JSONObject body = new JSONObject().put("contents", new JSONArray()
                .put(new JSONObject().put("parts", parts0)));

        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                + URLEncoder.encode(model, "UTF-8") + ":generateContent");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000); c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", apiKey);
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
        String resp = bo.toString("UTF-8");
        if (code >= 400) {
            String msg = resp;
            try { msg = new JSONObject(resp).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
            throw new Exception("Gemini 오류 " + code + ": " + msg);
        }
        JSONArray parts = new JSONObject(resp).getJSONArray("candidates")
                .getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text"));
        return sb.toString().trim();
    }
}
