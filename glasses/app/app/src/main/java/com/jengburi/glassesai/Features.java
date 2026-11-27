package com.jengburi.glassesai;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 재미 기능 모음: 길안내 · 내 위치 · 주변 검색 · 주차 기억 · 칼로리 기록 · 오늘 일기 · 팩트 체크 · 회의 기록.
 */
public class Features {
    static final String APPNAME = "com.jengburi.glassesai";
    private final AppCore core;
    public volatile boolean meetingOn;

    Features(AppCore core) { this.core = core; }

    // ================= 위치 =================

    @SuppressLint("MissingPermission")
    Location location() {
        LocationManager lm = (LocationManager) core.ctx.getSystemService(android.content.Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (Exception ignored) { }
        if (best != null && System.currentTimeMillis() - best.getTime() < 60_000) return best;
        // 오래된 위치면 새로 받기 (최대 8초)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                String prov = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
                CountDownLatch l = new CountDownLatch(1);
                final Location[] got = {null};
                ExecutorService ex = Executors.newSingleThreadExecutor();
                lm.getCurrentLocation(prov, null, ex, loc -> { got[0] = loc; l.countDown(); });
                l.await(8, TimeUnit.SECONDS);
                ex.shutdown();
                if (got[0] != null) return got[0];
            } catch (Exception ignored) { }
        }
        return best;
    }

    String address(double lat, double lng) {
        try {
            List<Address> a = new Geocoder(core.ctx, Locale.KOREA).getFromLocation(lat, lng, 1);
            if (a != null && !a.isEmpty()) return a.get(0).getAddressLine(0).replace("대한민국 ", "");
        } catch (Exception ignored) { }
        return null;
    }

    String whereAmI() {
        Location l = location();
        if (l == null) return "위치를 찾지 못했어요. 휴대폰 위치(GPS)가 켜져 있는지 확인해 주세요.";
        String a = address(l.getLatitude(), l.getLongitude());
        if (a == null) return "위치는 찾았는데 주소를 알아내지 못했어요. 인터넷 연결을 확인해 주세요.";
        return "지금 " + a + " 근처예요.";
    }

    // ================= 지도 =================

    boolean naver() { return !core.prefs.getString("mapApp", "네이버").contains("구글"); }

    /** 화면이 꺼져 있거나 앱이 뒤에 있으면 알림으로 대신 띄움. 지도를 열었으면 true. */
    boolean open(Intent i, String what) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean canLaunch = core.ui != null || Settings.canDrawOverlays(core.ctx);
        if (canLaunch) {
            try { core.ctx.startActivity(i); return true; }
            catch (ActivityNotFoundException e) { return false; }
            catch (Exception e) { core.log("지도 바로 열기 실패, 알림으로 보낼게요: " + e.getMessage()); }
        }
        try {
            NotificationManager nm = core.ctx.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("nav", "길안내", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(core.ctx, (int) (System.currentTimeMillis() & 0xffff), i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(core.ctx, "nav")
                    .setContentTitle("🗺 " + what)
                    .setContentText("누르면 지도가 열려요")
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).build();
            nm.notify(7, n);
            core.log("휴대폰 알림을 누르면 지도가 열려요 (설정에서 '다른 앱 위에 표시'를 허용하면 바로 열려요)");
        } catch (Exception e) { core.log("알림 실패: " + e.getMessage()); }
        return true;
    }

    private static String enc(String s) { return Uri.encode(s); }

    String navigate(String dest, String how) {
        if (dest == null || dest.trim().isEmpty()) return "어디로 갈지 못 들었어요. 목적지를 말해 주세요.";
        dest = dest.trim();
        boolean walk = how == null || how.contains("도보") || how.contains("걸");
        boolean pub = how != null && (how.contains("대중") || how.contains("버스") || how.contains("지하철"));
        String type = pub ? "대중교통" : walk ? "걸어서" : "자동차로";
        Location here = location();
        if (naver()) {
            double[] d = geocode(dest, here);
            String url;
            if (d == null) url = "nmap://search?query=" + enc(dest) + "&appname=" + APPNAME;
            else {
                String q = "?dlat=" + d[0] + "&dlng=" + d[1] + "&dname=" + enc(dest) + "&appname=" + APPNAME;
                url = pub ? "nmap://route/public" + q : walk ? "nmap://route/walk" + q : "nmap://navigation" + q;
            }
            if (open(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), dest + " 길안내")) {
                if (d == null) return "네이버 지도에서 " + dest + "을 찾았어요. 장소를 고르고 길찾기를 눌러 주세요.";
                return pub || walk ? "네이버 지도에 " + dest + "까지 " + type + " 가는 길을 띄웠어요. 안내 시작을 눌러 주세요."
                        : dest + "까지 자동차 길안내를 시작할게요.";
            }
            core.log("네이버 지도가 없어서 구글 지도로 열게요");
        }
        String url = pub ? "https://www.google.com/maps/dir/?api=1&destination=" + enc(dest) + "&travelmode=transit"
                : "google.navigation:q=" + enc(dest) + "&mode=" + (walk ? "w" : "d");
        if (open(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), dest + " 길안내"))
            return "구글 지도로 " + dest + "까지 " + type + " 길안내를 시작할게요.";
        return "지도 앱이 없어서 길안내를 못 했어요. 네이버 지도를 설치해 주세요.";
    }

    /** 장소 이름 → 좌표 (현재 위치 근처를 먼저 찾음) */
    double[] geocode(String name, Location near) {
        try {
            Geocoder g = new Geocoder(core.ctx, Locale.KOREA);
            List<Address> a = null;
            if (near != null) {
                double la = near.getLatitude(), ln = near.getLongitude();
                a = g.getFromLocationName(name, 1, la - 0.3, ln - 0.3, la + 0.3, ln + 0.3);
            }
            if (a == null || a.isEmpty()) a = g.getFromLocationName(name, 1);
            if (a != null && !a.isEmpty()) return new double[]{a.get(0).getLatitude(), a.get(0).getLongitude()};
        } catch (Exception ignored) { }
        return null;
    }

    String nearby(String what) {
        if (what == null || what.trim().isEmpty()) what = "맛집";
        String url = naver() ? "nmap://search?query=" + enc(what) + "&appname=" + APPNAME : "geo:0,0?q=" + enc(what);
        if (open(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), "근처 " + what))
            return "지도에 근처 " + what + "을 띄웠어요.";
        return "지도 앱이 없어요.";
    }

    // ================= 주차 =================

    /** 위치 저장 + (가능하면) 사진으로 기둥 번호 읽기 */
    String saveParking() {
        Location l = location();
        String time = new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date());
        android.content.SharedPreferences.Editor e = core.prefs.edit().putString("parkTime", time).putString("parkNote", "");
        if (l != null) e.putString("parkLat", "" + l.getLatitude()).putString("parkLng", "" + l.getLongitude());
        else e.remove("parkLat").remove("parkLng");
        e.apply();
        String note = "";
        if (core.ble.canFetchPhoto()) {
            try {
                core.say("주차 위치를 찍어 둘게요");
                byte[] jpeg = core.capturePhotoPublic();
                core.wifi.waitForInternet(20);
                note = core.askModel("이 사진은 주차장에서 방금 찍은 거야. 층수, 기둥 번호, 구역 표시처럼 차를 다시 찾는 데 도움이 되는 글자만 아주 짧게 말해 줘. "
                        + "예: '지하 2층 C3 기둥'. 보이는 게 없으면 none.", jpeg, null).trim();
                if (note.toLowerCase().startsWith("none")) note = "";
                core.prefs.edit().putString("parkNote", note).apply();
                History.save(core.ctx, jpeg, "주차 위치", null, note.isEmpty() ? "주차 위치 사진" : note, core.toGallery());
            } catch (Exception ex) { core.log("주차 사진 실패: " + ex.getMessage()); }
        }
        return "주차 위치를 기억했어요" + (note.isEmpty() ? "." : ". " + note + "예요.");
    }

    String findCar() {
        String time = core.prefs.getString("parkTime", "");
        if (time.isEmpty()) return "기억해 둔 주차 위치가 없어요. 차에서 내릴 때 '쟁부리야, 주차' 라고 말해 주세요.";
        String note = core.prefs.getString("parkNote", "");
        String lat = core.prefs.getString("parkLat", ""), lng = core.prefs.getString("parkLng", "");
        String msg = time + "에 세웠어요." + (note.isEmpty() ? "" : " " + note + "예요.");
        if (!lat.isEmpty()) {
            String url = naver()
                    ? "nmap://route/walk?dlat=" + lat + "&dlng=" + lng + "&dname=" + enc("내 차") + "&appname=" + APPNAME
                    : "google.navigation:q=" + lat + "," + lng + "&mode=w";
            if (open(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), "내 차 찾기")) msg += " 지도에 걸어가는 길을 띄웠어요.";
        }
        return msg;
    }

    // ================= 칼로리 =================

    static String today() { return new SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(new Date()); }

    private void rollDay() {
        if (!today().equals(core.prefs.getString("kcalDate", "")))
            core.prefs.edit().putString("kcalDate", today()).putInt("kcalTotal", 0).putInt("kcalCount", 0).apply();
    }

    public int kcalTotal() { rollDay(); return core.prefs.getInt("kcalTotal", 0); }
    public int kcalCount() { rollDay(); return core.prefs.getInt("kcalCount", 0); }

    void addKcal(int n) {
        if (n <= 0) return;
        rollDay();
        core.prefs.edit().putInt("kcalTotal", kcalTotal() + n).putInt("kcalCount", kcalCount() + 1).apply();
        core.log("🍽 칼로리 기록: +" + n + "kcal (오늘 " + kcalTotal() + "kcal, " + kcalCount() + "번째)");
    }

    public void resetKcal() { core.prefs.edit().putString("kcalDate", today()).putInt("kcalTotal", 0).putInt("kcalCount", 0).apply(); }

    String kcalContext() {
        int t = kcalTotal(), c = kcalCount();
        return c == 0 ? "오늘은 아직 기록된 음식이 없어." : "오늘 지금까지 " + c + "번 먹었고 합계 " + t + "kcal야.";
    }

    /** 대답 속 [칼로리:650] 을 찾아 기록하고, 읽을 글에서는 지움 */
    String takeKcal(String a) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[칼로리\\s*[:：]\\s*(\\d+)\\s*(kcal)?\\]").matcher(a);
        if (m.find()) { try { addKcal(Integer.parseInt(m.group(1))); } catch (Exception ignored) { } }
        return a.replaceAll("\\[칼로리[^\\]]*\\]", "").trim();
    }

    // ================= 오늘 일기 =================

    String diary() throws Exception {
        String day = today();
        StringBuilder sb = new StringBuilder();
        for (History.Entry e : History.list(core.ctx, 120)) {
            if (e.id == null || !e.id.startsWith(day) || "오늘 일기".equals(e.mode)) continue;
            sb.insert(0, e.time + " [" + e.mode + "] " + (e.question == null || e.question.isEmpty() ? "" : "질문: " + e.question + " / ")
                    + "AI: " + (e.answer.length() > 200 ? e.answer.substring(0, 200) : e.answer) + "\n");
        }
        if (sb.length() == 0) return "오늘은 아직 기록이 없어서 일기를 쓸 게 없어요. 안경으로 이것저것 보고 물어본 뒤에 다시 불러 주세요.";
        String p = "다음은 내가 오늘 AI 안경으로 보고 물어본 기록이야 (시간순).\n" + sb
                + "\n이걸로 나의 오늘 하루 일기를 써 줘. 1인칭, 따뜻하고 살짝 유머 있게, 5~7문장. "
                + "맨 앞에 짧은 제목 한 줄. 마크다운이나 기호는 쓰지 마. 블로그 초안으로도 쓸 거야."
                + "\n오늘 먹은 음식 기록: " + kcalContext();
        String d = core.askModel(p, null, null).trim();
        History.save(core.ctx, null, "오늘 일기", null, d, false);
        core.uploadMoment(null, "오늘 일기", null, d);
        return d;
    }

    // ================= 팩트 체크 =================

    String factCheck() throws Exception {
        core.sayAndWait("확인할 말을 들려주세요", 5);
        byte[] wav = AudioQ.record(core.ctx, false, 10, 25, core::log);
        if (wav == null) return "아무 말도 못 들었어요.";
        core.answer("팩트 체크 중…");
        return core.askModel("첨부 오디오는 TV, 유튜브, 대화에서 들린 말이야. 그 안의 주장이 사실인지 팩트 체크해 줘.\n"
                + "첫마디는 '맞아요', '틀려요', '반만 맞아요', '확인이 어려워요' 중 하나로 시작하고, 이유를 2~3문장으로. "
                + "최신 뉴스처럼 네가 확실히 모르는 건 모른다고 솔직히 말해." + AppCore.COMMON_TAIL, null, wav).trim();
    }

    // ================= 회의 기록 =================

    public void startMeeting() {
        if (meetingOn || core.busy) return;
        if (core.key().isEmpty()) { core.say("먼저 앱에 제미나이 API 키를 넣어 주세요"); return; }
        meetingOn = true;
        core.setBusy(true);
        new Thread(this::meetingLoop).start();
    }

    public void stopMeeting() { if (meetingOn) { meetingOn = false; core.log("■ 회의 기록 끝내는 중… 요약을 만들고 있어요"); } }

    @SuppressLint("MissingPermission")
    private void meetingLoop() {
        final int RATE = 16000;
        AudioRecord rec = null;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        final StringBuilder transcript = new StringBuilder();
        long start = System.currentTimeMillis();
        try {
            core.sayAndWait("회의 기록을 시작할게요. 끝낼 때는 안경 버튼이나 멈추기를 눌러 주세요.", 8);
            core.log("▶ 회의 기록 시작 (휴대폰 마이크, 1분마다 받아쓰기)");
            core.answer("🎙 회의 기록 중…");
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2));
            rec.startRecording();
            short[] buf = new short[RATE / 10];
            ByteArrayOutputStream chunk = new ByteArrayOutputStream();
            float g = AudioQ.gain(core.ctx);
            int frames = 0, part = 0;
            while (meetingOn && System.currentTimeMillis() - start < 90 * 60_000L) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                AudioQ.applyGain(buf, n, g);
                double sum = 0; for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                if (++frames % 10 == 0) core.micRms = Math.sqrt(sum / n);
                byte[] b = AudioQ.toBytes(buf, n);
                chunk.write(b, 0, b.length);
                if (frames >= 600) { // 1분
                    submit(pool, chunk.toByteArray(), ++part, transcript);
                    chunk.reset(); frames = 0;
                    int mins = (int) ((System.currentTimeMillis() - start) / 60000);
                    core.answer("🎙 회의 기록 중… " + mins + "분째");
                }
            }
            if (frames > 30) submit(pool, chunk.toByteArray(), ++part, transcript);
            rec.stop(); rec.release(); rec = null;
            core.say("회의 내용을 정리할게요");
            pool.shutdown();
            pool.awaitTermination(180, TimeUnit.SECONDS);
            String t;
            synchronized (transcript) { t = transcript.toString().trim(); }
            if (t.isEmpty()) { core.say("받아 적은 내용이 없어요"); return; }
            String summary = core.askModel("다음은 회의를 받아 적은 거야.\n" + t
                    + "\n\n귀로 들을 요약을 만들어 줘: 결론 한 문장, 할 일(누가·무엇을) 최대 3개, 중요한 숫자나 날짜. 짧게 말하듯이."
                    + AppCore.COMMON_TAIL, null, null).trim();
            core.answer(summary);
            core.speakQueued(summary, Locale.KOREAN);
            String full = summary + "\n\n----- 받아쓰기 -----\n" + t;
            History.save(core.ctx, null, "회의 기록", null, full, false);
            core.uploadMoment(null, "회의 기록", null, full);
            if (core.ui != null) core.ui.onHistoryChanged();
        } catch (Exception e) {
            core.log("회의 기록 오류: " + e.getMessage());
            core.say("회의 기록 중 오류가 났어요");
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
            pool.shutdownNow();
            meetingOn = false;
            core.micRms = -1;
            core.setBusy(false);
        }
    }

    private void submit(ExecutorService pool, byte[] pcm, int part, StringBuilder transcript) {
        byte[] wav = AudioQ.wav(pcm);
        pool.submit(() -> {
            try {
                String t = core.askModel("이 오디오는 회의 녹음의 " + part + "번째 1분이야. 들린 말을 한국어로 받아 적어 줘. "
                        + "말하는 사람이 바뀌면 줄을 바꾸고 '가:', '나:'처럼 구분해. 외국어면 한국어로 옮겨 적어. 말이 없으면 none.", null, wav).trim();
                if (!t.toLowerCase().startsWith("none")) synchronized (transcript) { transcript.append(t).append('\n'); }
                core.log("회의 " + part + "분 받아쓰기 완료");
            } catch (Exception e) { core.log("회의 " + part + "분 받아쓰기 실패: " + e.getMessage()); }
        });
    }
}
