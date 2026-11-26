package com.jengburi.glassesai;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 안경 핫스팟(비밀번호 12345678, 주소 192.168.169.1) 접속과 사진 다운로드. */
public class GlassesWifi {
    static final String PASSWORD = "12345678";
    static final String BASE = "http://192.168.169.1/";

    private final Context ctx;
    private final GlassesBle.Log log;
    private final ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback cb;
    private Network network;

    public GlassesWifi(Context ctx, GlassesBle.Log log) {
        this.ctx = ctx; this.log = log;
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void connect(String ssid) throws Exception {
        WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid).setWpa2Passphrase(PASSWORD).build();
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec).build();
        CountDownLatch ok = new CountDownLatch(1);
        final String[] err = {null};
        cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { network = n; ok.countDown(); }
            @Override public void onUnavailable() { err[0] = "안경 와이파이 연결을 거절했거나 찾지 못했어요"; ok.countDown(); }
        };
        log.log("안경 와이파이 연결 중… (처음엔 '연결 허용' 창이 떠요)");
        cm.requestNetwork(req, cb, 60000);
        if (!ok.await(65, TimeUnit.SECONDS) || network == null)
            throw new Exception(err[0] != null ? err[0] : "안경 와이파이 연결 시간 초과");
        log.log("안경 와이파이 연결 완료");
    }

    private byte[] get(String path, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) network.openConnection(new URL(BASE + path));
        c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally { c.disconnect(); }
    }

    /** 가장 최근 사진의 파일 이름(예: EVENT/2026...jpg). 없으면 null. */
    public String latestPhoto() throws Exception {
        String body = new String(get("app/getfilelist", 10000), "UTF-8");
        JSONObject root = new JSONObject(body);
        JSONArray info = root.optJSONArray("info");
        String best = null, bestTime = "";
        if (info != null) for (int i = 0; i < info.length(); i++) {
            JSONArray files = info.getJSONObject(i).optJSONArray("files");
            if (files == null) continue;
            for (int j = 0; j < files.length(); j++) {
                JSONObject f = files.getJSONObject(j);
                String name = f.optString("name");
                if (!name.toLowerCase().endsWith(".jpg")) continue;
                String t = f.optString("createtimestr", name);
                if (t.compareTo(bestTime) >= 0) { bestTime = t; best = name; }
            }
        }
        log.log(best != null ? "최근 사진: " + best : "안경에 사진이 없어요");
        return best;
    }

    public byte[] download(String name) throws Exception {
        byte[] b = get(name, 20000);
        log.log("사진 받음 (" + (b.length / 1024) + "KB)");
        return b;
    }

    public void disconnect() {
        if (cb != null) { try { cm.unregisterNetworkCallback(cb); } catch (Exception ignored) { } }
        cb = null; network = null;
    }

    /** 집 와이파이 등 인터넷 되는 네트워크로 돌아올 때까지 대기. */
    public boolean waitForInternet(int sec) throws InterruptedException {
        long end = System.currentTimeMillis() + sec * 1000L;
        while (System.currentTimeMillis() < end) {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true;
            Thread.sleep(500);
        }
        return false;
    }
}
