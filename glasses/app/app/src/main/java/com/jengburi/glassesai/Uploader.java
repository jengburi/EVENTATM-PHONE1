package com.jengburi.glassesai;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 구글 앱스 스크립트 웹 앱으로 파일을 보내 드라이브 '안경 촬영/날짜' 폴더에 저장. */
public class Uploader {
    /** bytes 가 null 이면 text 를 텍스트 파일로 저장. 성공하면 드라이브 파일 주소, 실패하면 예외. */
    public static String upload(String webAppUrl, String key, String name, String mime, byte[] bytes, String text) throws Exception {
        JSONObject body = new JSONObject().put("key", key).put("name", name);
        if (bytes != null) body.put("mime", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
        else body.put("text", text == null ? "" : text);

        HttpURLConnection c = (HttpURLConnection) new URL(webAppUrl).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000); c.setReadTimeout(120000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
        int code = c.getResponseCode();
        String resp;
        if (code == 301 || code == 302 || code == 303) {
            // 앱스 스크립트는 결과를 다른 주소로 넘겨줌 → 따라가서 결과 읽기
            String loc = c.getHeaderField("Location");
            c.disconnect();
            HttpURLConnection g = (HttpURLConnection) new URL(loc).openConnection();
            g.setConnectTimeout(20000); g.setReadTimeout(60000);
            resp = read(g.getInputStream());
            g.disconnect();
        } else if (code < 400) {
            resp = read(c.getInputStream());
        } else {
            throw new Exception("업로드 오류 " + code + ": " + read(c.getErrorStream()));
        }
        JSONObject j;
        try { j = new JSONObject(resp); }
        catch (Exception e) { throw new Exception("업로드 창구 응답이 이상해요 (주소를 확인해 주세요)"); }
        if (!j.optBoolean("ok")) throw new Exception("업로드 실패: " + j.optString("error"));
        return j.optString("url");
    }

    public static String test(String webAppUrl) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(webAppUrl).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        return read(c.getInputStream());
    }

    static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toString("UTF-8");
    }
}
