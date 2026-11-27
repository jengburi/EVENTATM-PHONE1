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

    /** 휴대폰이 이미 이 와이파이에 붙어 있으면 그 네트워크를 돌려줌 */
    @SuppressWarnings("deprecation")
    private Network findConnected(String ssid) {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
            String cur = wi == null ? null : wi.getSSID();
            if (cur == null || !cur.replace("\"", "").equals(ssid)) return null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            }
        } catch (Exception ignored) { }
        return null;
    }

    public void connect(String ssid) throws Exception {
        Network already = findConnected(ssid);
        if (already != null) { network = already; log.log("이미 " + ssid + " 에 연결돼 있어요"); return; }
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
        log.log("연결 창이 뜨면 목록에 " + ssid + " 가 나타날 때까지 기다렸다가 '연결'을 눌러 주세요");
        long t0 = System.currentTimeMillis();
        cm.requestNetwork(req, cb, 90000);
        ok.await(95, TimeUnit.SECONDS);
        if (network != null) { log.log("안경 와이파이 연결 완료"); return; }
        log.log("연결 창 방식 실패 (" + ((System.currentTimeMillis() - t0) / 1000) + "초): " + (err[0] == null ? "시간 초과" : err[0]));
        try { cm.unregisterNetworkCallback(cb); } catch (Exception ignored) { }
        cb = null;
        // 대신: 사용자가 설정에서 직접 연결하면 그 네트워크를 사용
        log.log("👉 설정 → Wi-Fi 에서 " + ssid + " 를 눌러 직접 연결해 주세요 (비밀번호 " + PASSWORD + "). 45초 기다릴게요");
        long end = System.currentTimeMillis() + 45000;
        while (System.currentTimeMillis() < end) {
            Network n = findConnected(ssid);
            if (n != null) { network = n; log.log("직접 연결 확인됨: " + ssid); return; }
            Thread.sleep(1000);
        }
        throw new Exception(err[0] != null ? err[0] : "안경 와이파이 연결 시간 초과");
    }

    public boolean usingOwnRequest() { return cb != null; }

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

    /** 안경에 있는 모든 파일 (이름, 크기) — 사진·동영상·녹음 */
    public java.util.List<String[]> listAll() throws Exception {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        JSONObject root = new JSONObject(new String(get("app/getfilelist", 10000), "UTF-8"));
        JSONArray info = root.optJSONArray("info");
        if (info != null) for (int i = 0; i < info.length(); i++) {
            JSONArray files = info.getJSONObject(i).optJSONArray("files");
            if (files == null) continue;
            for (int j = 0; j < files.length(); j++) {
                JSONObject f = files.getJSONObject(j);
                out.add(new String[]{f.optString("name"), String.valueOf(f.optLong("size")), f.optString("createtimestr")});
            }
        }
        return out;
    }

    /** 큰 파일은 메모리 대신 파일로 저장 */
    public void downloadTo(String name, java.io.File dst) throws Exception {
        HttpURLConnection c = (HttpURLConnection) network.openConnection(new URL(BASE + name));
        c.setConnectTimeout(15000); c.setReadTimeout(60000);
        try (InputStream in = c.getInputStream(); java.io.FileOutputStream o = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } finally { c.disconnect(); }
    }

    // ---- M88 (FTP) ----
    /** 와이파이에 붙은 직후엔 안경 FTP 서버가 아직 안 떠 있을 수 있어서 1초 간격으로 다시 시도 */
    private Ftp openFtp() throws Exception {
        Exception last = null;
        for (int i = 1; i <= 15; i++) {
            try {
                Ftp f = new Ftp(network);
                if (i > 1) log.log("안경 파일 서버 접속 성공 (" + i + "번째)");
                return f;
            } catch (Exception e) {
                last = e;
                if (i == 1) log.log("안경 파일 서버 준비 기다리는 중…");
                Thread.sleep(1000);
            }
        }
        throw new Exception("안경 파일 서버에 접속하지 못했어요: " + (last == null ? "" : last.getMessage()));
    }
    public String latestPhotoFtp() throws Exception {
        try (Ftp f = openFtp()) {
            String best = null;
            for (String n : f.list()) {
                String l = n.toLowerCase();
                if (!(l.endsWith(".jpg") || l.endsWith(".jpeg"))) continue;
                if (best == null || n.compareTo(best) > 0) best = n;
            }
            log.log(best != null ? "최근 사진: " + best : "안경에 사진이 없어요");
            return best;
        }
    }
    /** 안경에 있는 가장 최근 동영상 (.mp4) */
    public String latestVideoFtp() throws Exception {
        try (Ftp f = openFtp()) {
            String best = null;
            for (String n : f.list()) {
                if (!n.toLowerCase().endsWith(".mp4")) continue;
                if (best == null || n.compareTo(best) > 0) best = n;
            }
            log.log(best != null ? "최근 영상: " + best : "안경에 영상이 없어요");
            return best;
        }
    }

    public byte[] downloadFtp(String name) throws Exception {
        try (Ftp f = openFtp()) {
            byte[] b = f.retr(name);
            log.log("사진 받음 (" + (b.length / 1024) + "KB)");
            return b;
        }
    }
    public java.util.List<String> listFtp() throws Exception { try (Ftp f = openFtp()) { return f.list(); } }
    public void downloadFtpTo(String name, java.io.File dst) throws Exception { try (Ftp f = openFtp()) { f.retrTo(name, dst); } }

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
