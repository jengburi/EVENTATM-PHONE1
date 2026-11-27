package com.jengburi.glassesai;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * S1Pro 안경 BLE 명령 채널 (서비스 0xAA12).
 * 패킷: 보내기 AB 55 00 LEN CMD DATA.. CHK / 받기 AC 55 00 LEN CMD DATA.. CHK
 * CHK = (CMD + DATA) 합의 하위 1바이트, LEN = CMD+DATA+CHK 바이트 수.
 * 모든 메서드는 백그라운드 스레드에서 호출해야 한다(블로킹).
 */
@SuppressLint("MissingPermission")
public class GlassesBle {
    static final UUID SERVICE = uuid16(0xAA12);
    static final UUID CMD_CHAR = uuid16(0xAA13);
    static final UUID NOTIFY_CHAR = uuid16(0xAA14);
    static final UUID NOTIFY2_CHAR = uuid16(0xAA15);
    static final UUID CCCD = uuid16(0x2902);

    public static final int CMD_TAKE_PHOTO = 0x22;   // DATA 0x30
    public static final int EVT_MEDIA_COUNT = 0x42;   // 촬영 완료/미디어 개수
    public static final int CMD_WIFI_ON = 0x39;       // DATA 0x30 (AP 모드)
    public static final int EVT_WIFI_SSID = 0x25;     // ASCII SSID
    public static final int CMD_TRANSFER_END = 0x44;  // DATA 0x30 0x01 = 닫기(파일 유지)

    public interface Log { void log(String s); }
    /** 안경에서 오는 모든 알림 패킷을 받는 리스너 (버튼 감지용). */
    public interface PacketListener { void onPacket(byte[] p); }
    public volatile PacketListener listener;

    private final Context ctx;
    private final Log log;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cmdChar;
    private final LinkedBlockingQueue<byte[]> packets = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<String> gattEvents = new LinkedBlockingQueue<>();

    public GlassesBle(Context ctx, Log log) { this.ctx = ctx; this.log = log; }

    static UUID uuid16(int v) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v));
    }

    public static byte[] packet(int cmd, int... data) {
        byte[] p = new byte[4 + 1 + data.length + 1];
        p[0] = (byte) 0xAB; p[1] = 0x55; p[2] = 0x00; p[3] = (byte) (data.length + 2);
        p[4] = (byte) cmd;
        int sum = cmd;
        for (int i = 0; i < data.length; i++) { p[5 + i] = (byte) data[i]; sum += data[i]; }
        p[p.length - 1] = (byte) (sum & 0xFF);
        return p;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    public boolean isConnected() { return gatt != null && cmdChar != null; }

    /** 같은 방식(DANHAI)으로 알려진 안경 이름 앞부분 */
    static final String[] KNOWN = {"S1PRO", "M88"};

    /** 앱에서 고른 안경 주소 (비어 있으면 이름으로 자동 선택) */
    public volatile String preferredAddress = "";

    /** 페어링된 블루투스 기기 목록 (이름, 주소) */
    public java.util.List<String[]> bondedList() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            for (BluetoothDevice d : bm.getAdapter().getBondedDevices())
                out.add(new String[]{d.getName() == null ? "(이름 없음)" : d.getName(), d.getAddress()});
        } catch (Exception ignored) { }
        return out;
    }

    /** 고른 안경 → 없으면 S1Pro/M88 이름으로 자동 선택. */
    public synchronized void connect() throws Exception {
        if (isConnected()) return;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm.getAdapter();
        if (ad == null || !ad.isEnabled()) throw new Exception("블루투스가 꺼져 있어요");
        BluetoothDevice dev = null;
        String pref = preferredAddress;
        for (BluetoothDevice d : ad.getBondedDevices()) {
            if (!pref.isEmpty() && pref.equalsIgnoreCase(d.getAddress())) { dev = d; break; }
        }
        if (dev == null) {
            for (BluetoothDevice d : ad.getBondedDevices()) {
                String n = d.getName() == null ? "" : d.getName().toUpperCase().replace(" ", "");
                for (String k : KNOWN) if (n.startsWith(k) || n.contains(k)) { dev = d; break; }
                if (dev != null) break;
            }
        }
        if (dev == null) throw new Exception("연결할 안경을 찾지 못했어요. 앱의 '안경 고르기'에서 안경을 선택해 주세요");
        log.log("안경 연결 중: " + dev.getName());
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) { } gatt = null; }
        gattEvents.clear();
        gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE);
        waitEvent("connected", 15);
        gatt.discoverServices();
        waitEvent("services", 15);
        BluetoothGattService s = gatt.getService(SERVICE);
        BluetoothGattService j = gatt.getService(JY_CAM_SERVICE);
        if (s != null) {
            jianyun = false;
            cmdChar = s.getCharacteristic(CMD_CHAR);
            enableNotify(s.getCharacteristic(NOTIFY_CHAR));
            enableNotify(s.getCharacteristic(NOTIFY2_CHAR));
            log.log("안경 연결 완료 (S1Pro 방식)");
        } else if (j != null) {
            jianyun = true;
            cmdChar = j.getCharacteristic(JY_CAM_WRITE);
            enableNotify(j.getCharacteristic(JY_CAM_NOTIFY));
            BluetoothGattService base = gatt.getService(JY_BASE_SERVICE);
            if (base != null) {
                enableNotify(base.getCharacteristic(JY_BASE_NOTIFY));
                jyBaseWrite = base.getCharacteristic(JY_BASE_WRITE);
            }
            jyName = dev.getName() == null ? "M88" : dev.getName();
            jyMac = dev.getAddress().replace(":", "");
            log.log("안경 연결 완료 (M88 방식)");
        } else {
            throw new Exception("이 안경의 명령 통로(AA12 / 57FF)를 찾지 못했어요. 아직 지원하지 않는 방식이에요");
        }
    }

    // ---- M88 (JIANYUN) 방식 ----
    static final UUID JY_BASE_SERVICE = uuid16(0x56FF), JY_BASE_NOTIFY = uuid16(0x33F4), JY_BASE_WRITE = uuid16(0x33F3);
    private BluetoothGattCharacteristic jyBaseWrite;
    private String jyName = "M88", jyMac = "";

    /** M88 기본 채널(33F3)로 20바이트 명령 */
    void sendBase(byte[] p20) throws Exception {
        if (!isConnected()) connect();
        if (jyBaseWrite == null) throw new Exception("M88 기본 채널을 찾지 못했어요");
        jyBaseWrite.setValue(p20);
        jyBaseWrite.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        gattEvents.clear();
        if (!gatt.writeCharacteristic(jyBaseWrite)) throw new Exception("명령 전송 실패");
        waitEvent("write", 5);
        log.log("→ " + hex(p20));
    }
    static byte[] p20(int... b) { byte[] p = new byte[20]; for (int i = 0; i < b.length; i++) p[i] = (byte) b[i]; return p; }
    static final UUID JY_CAM_SERVICE = uuid16(0x57FF), JY_CAM_WRITE = uuid16(0x33F5), JY_CAM_NOTIFY = uuid16(0x33F6);
    public volatile boolean jianyun;
    /** M88 은 아직 사진을 휴대폰으로 가져오는 방법을 모름 */
    public boolean canFetchPhoto() { return true; }

    /** M88 명령: TT 00 01 00 01 00 01 00 VV + 0 채움 (20바이트) */
    static byte[] jyPacket(int type, int value) {
        byte[] p = new byte[20];
        p[0] = (byte) type; p[2] = 1; p[4] = 1; p[6] = 1; p[8] = (byte) value;
        return p;
    }

    /** M88 AI 버튼 신호인지 (01 00 01 00 01 00 01 00 01) */
    public static boolean isJyAiButton(byte[] p) {
        return p.length >= 9 && p[0] == 1 && p[1] == 0 && p[2] == 1 && p[4] == 1 && p[6] == 1 && p[8] == 1;
    }

    /** M88 안경이 자체 AI 모드로 들어가지 않게 바로 끝내기 */
    public void jyEndAiSession() {
        try { send(jyPacket(0x08, 0x00)); } catch (Exception ignored) { }
    }

    private void enableNotify(BluetoothGattCharacteristic c) throws Exception {
        if (c == null) return;
        gatt.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) return;
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        gatt.writeDescriptor(d);
        waitEvent("desc", 5);
    }

    private void waitEvent(String name, int sec) throws Exception {
        long end = System.currentTimeMillis() + sec * 1000L;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) throw new Exception("안경 응답 시간 초과 (" + name + ")");
            String e = gattEvents.poll(left, TimeUnit.MILLISECONDS);
            if (e == null) continue;
            if (e.equals("disconnected")) throw new Exception("안경 연결이 끊겼어요");
            if (e.equals(name)) return;
        }
    }

    public void send(byte[] p) throws Exception {
        if (!isConnected()) connect();
        cmdChar.setValue(p);
        cmdChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        gattEvents.clear();
        if (!gatt.writeCharacteristic(cmdChar)) throw new Exception("명령 전송 실패");
        waitEvent("write", 5);
        log.log("→ " + hex(p));
    }

    /** 특정 CMD 의 안경 응답을 기다린다. 데이터 부분(CMD 다음~CHK 앞)을 돌려준다. */
    public byte[] waitFor(int cmd, int sec) throws Exception {
        long end = System.currentTimeMillis() + sec * 1000L;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return null;
            byte[] p = packets.poll(left, TimeUnit.MILLISECONDS);
            if (p == null) return null;
            if (p.length >= 6 && (p[0] & 0xFF) == 0xAC && (p[4] & 0xFF) == cmd) {
                byte[] data = new byte[p.length - 6];
                System.arraycopy(p, 5, data, 0, data.length);
                return data;
            }
        }
    }

    public void clearPackets() { packets.clear(); }

    /** 영상 녹화 중인지 (앱이 보낸 명령 기준) */
    public volatile boolean recording;

    /** 안경으로 영상 녹화 시작 (현재 M88만) */
    public void startVideo() throws Exception {
        if (!jianyun) throw new Exception("S1Pro 영상 찍기 신호는 아직 분석 전이에요 (M88만 돼요)");
        send(jyPacket(0x0a, 0x03));
        recording = true;
    }

    public void stopVideo() throws Exception {
        if (!jianyun) throw new Exception("S1Pro 영상 찍기는 아직 지원하지 않아요");
        send(jyPacket(0x0a, 0x04));
        recording = false;
    }

    public void takePhoto() throws Exception {
        clearPackets();
        if (jianyun) {
            send(jyPacket(0x0a, 0x02));
            // 파일 수 알림(54 06 NN)을 최대 8초 기다림
            long end = System.currentTimeMillis() + 8000;
            boolean ok = false;
            while (System.currentTimeMillis() < end) {
                byte[] p = packets.poll(500, TimeUnit.MILLISECONDS);
                if (p != null && p.length >= 3 && (p[0] & 0xFF) == 0x54 && p[1] == 0x06) { ok = true; break; }
            }
            log.log(ok ? "찰칵! 촬영 완료 (안경에 저장)" : "촬영 완료 신호는 못 받았지만 계속 진행");
            return;
        }
        send(packet(CMD_TAKE_PHOTO, 0x30));
        // 촬영 완료 알림(0x42)을 최대 8초 기다림
        byte[] r = waitFor(EVT_MEDIA_COUNT, 8);
        log.log(r != null ? "찰칵! 촬영 완료" : "촬영 완료 신호는 못 받았지만 계속 진행");
    }

    /** 안경 핫스팟을 켜고 SSID 를 돌려준다. */
    public String wifiOn() throws Exception {
        if (jianyun) {
            // 54 13 01 → 안경 와이파이(AP) 켜기, 54 13 02 / 54 04 02 = 준비 완료
            clearPackets();
            sendBase(p20(0x54, 0x13, 0x01));
            long end = System.currentTimeMillis() + 20000;
            boolean ready = false;
            while (System.currentTimeMillis() < end) {
                byte[] p = packets.poll(500, TimeUnit.MILLISECONDS);
                if (p == null || p.length < 3 || (p[0] & 0xFF) != 0x54) continue;
                log.log("안경 응답: " + hex(java.util.Arrays.copyOf(p, Math.min(p.length, 8))));
                if ((p[1] == 0x13 && p[2] == 0x02) || (p[1] == 0x04 && p[2] == 0x02)) { ready = true; break; }
            }
            String ssid = jyName + "-" + (jyMac.length() >= 4 ? jyMac.substring(jyMac.length() - 4) : jyMac);
            log.log((ready ? "안경 와이파이 준비됨: " : "준비 신호는 못 받았지만 시도: ") + ssid);
            Thread.sleep(2000); // 와이파이가 실제로 보이기까지 조금 더
            return ssid;
        }
        clearPackets();
        send(packet(CMD_WIFI_ON, 0x30));
        byte[] r = waitFor(EVT_WIFI_SSID, 10);
        if (r == null) throw new Exception("안경 와이파이 이름을 받지 못했어요");
        String ssid = new String(r, StandardCharsets.US_ASCII).trim();
        log.log("안경 와이파이: " + ssid);
        return ssid;
    }

    public void transferEnd() {
        if (jianyun) { try { sendBase(p20(0x54, 0x13, 0x00)); } catch (Exception ignored) { } return; }
        try { send(packet(CMD_TRANSFER_END, 0x30, 0x01)); } catch (Exception ignored) { }
    }

    public void close() {
        if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { } }
        gatt = null; cmdChar = null;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) gattEvents.offer("connected");
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) { cmdChar = null; gattEvents.offer("disconnected"); }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) { gattEvents.offer("services"); }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { gattEvents.offer("desc"); }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { gattEvents.offer("write"); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (value != null) deliver(value.clone());
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            // Android 12 이하에서 호출됨 (13 이상은 위 메서드)
            if (android.os.Build.VERSION.SDK_INT >= 33) return;
            byte[] v = c.getValue();
            if (v != null) deliver(v.clone());
        }
    };

    /** 안경 배터리 % (-1 = 아직 모름), 충전 중 여부 */
    public volatile int battery = -1;
    public volatile boolean charging;

    private void parseBattery(byte[] v) {
        try {
            if (jianyun) {
                // M88: 0b PP [충전]
                if (v.length >= 2 && v[0] == 0x0b) {
                    int b = v[1] & 0xFF;
                    if (b <= 100) { battery = b; charging = v.length > 2 && v[2] == 1; }
                }
            } else if (v.length >= 7 && (v[0] & 0xFF) == 0xAC) {
                int cmd = v[4] & 0xFF;
                if (cmd == 0x17 && v.length >= 8) {
                    // S1Pro: 숫자 두 글자(ASCII) + 충전 표시
                    int d1 = v[5] - '0', d2 = v[6] - '0';
                    if (d1 >= 0 && d1 <= 9 && d2 >= 0 && d2 <= 9) { battery = d1 * 10 + d2; charging = v[7] == 1; }
                    else if ((v[5] & 0xFF) <= 100) battery = v[5] & 0xFF;
                } else if (cmd == 0x53) {
                    int b = v[6] & 0xFF;
                    if (b <= 100) battery = b;
                }
            }
        } catch (Exception ignored) { }
    }

    /** 배터리 물어보기 (응답은 알림으로 와서 battery 에 저장됨) */
    public void requestBattery() {
        try {
            if (!isConnected()) return;
            if (jianyun) sendBase(p20(0x0b)); else send(packet(0x17, 0x00));
        } catch (Exception ignored) { }
    }

    private void deliver(byte[] v) {
        parseBattery(v);
        // 음성 데이터(0x46)는 양이 많아서 큐에는 넣지 않음
        boolean audio = (v.length > 4 && (v[4] & 0xFF) == 0x46) || (jianyun && v.length > 60 && v[0] == 0x02);
        if (!audio) packets.offer(v);
        PacketListener l = listener;
        if (l != null) { try { l.onPacket(v); } catch (Exception ignored) { } }
    }
}
