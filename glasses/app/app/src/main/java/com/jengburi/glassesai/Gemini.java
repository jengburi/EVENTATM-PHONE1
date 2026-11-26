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
    public static String ask(String apiKey, String model, String prompt, byte[] jpeg) throws Exception {
        JSONObject img = new JSONObject().put("inline_data", new JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)));
        JSONObject txt = new JSONObject().put("text", prompt);
        JSONObject body = new JSONObject().put("contents", new JSONArray()
                .put(new JSONObject().put("parts", new JSONArray().put(txt).put(img))));

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
