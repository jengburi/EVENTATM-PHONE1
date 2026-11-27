package com.jengburi.glassesai;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 말소리를 녹음해서 WAV 로 돌려준다 (말이 끝나면 자동으로 멈춤). Gemini 가 직접 듣게 할 때 사용. */
public class AudioQ {
    static final int RATE = 16000;
    /** true 로 바꾸면 진행 중인 녹음을 바로 멈춤 */
    public static volatile boolean cancel;

    public interface ChunkSink { void onChunk(byte[] wav); }

    /** 마이크 소리 키우기 배율 (설정 'micGain', 기본 3배) */
    static float gain(Context ctx) {
        try { return AppCore.get(ctx).prefs.getFloat("micGain", 3.0f); } catch (Exception e) { return 3.0f; }
    }

    /** 소리 키우기 (찢어지지 않게 부드럽게 잘라냄) */
    static void applyGain(short[] b, int n, float g) {
        if (g <= 1.01f) return;
        for (int i = 0; i < n; i++) {
            float v = b[i] * g;
            if (v > 32767f) v = 32767f; else if (v < -32768f) v = -32768f;
            b[i] = (short) v;
        }
    }

    /** 진행 기록용 소리 크기 막대 */
    static String meter(double rms) {
        int bars = (int) Math.min(5, Math.round(Math.log10(Math.max(rms, 1)) * 1.6));
        StringBuilder sb = new StringBuilder("🎤 ");
        for (int i = 0; i < 5; i++) sb.append(i < bars ? "▮" : "▯");
        return sb.append(rms < 80 ? "  (거의 안 들려요)" : "").toString();
    }
    public interface Running { boolean get(); }

    /**
     * 끊지 않고 계속 녹음하면서, 짧게 쉬는 곳(0.4초)이나 최대 8초마다 잘라서 sink 로 넘긴다.
     * running.get() 이 false 가 되면 끝.
     */
    @SuppressLint("MissingPermission")
    public static void stream(Context ctx, boolean glassesMic, Running running, ChunkSink sink, GlassesBle.Log log) {
        try { AppCore.get(ctx).wakeWord.pauseNow(); } catch (Exception ignored) { }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        boolean sco = glassesMic && useGlassesMic(am, log);
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(sco ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.MIC,
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2));
            rec.startRecording();
            log.log((sco ? "안경" : "휴대폰") + " 마이크로 계속 듣는 중…");
            int frame = RATE / 10;
            short[] buf = new short[frame];
            ByteArrayOutputStream chunk = new ByteArrayOutputStream();
            double noise = -1;
            int frames = 0, speech = 0, silent = 0, total = 0;
            float g = gain(ctx);
            double meterSum = 0;
            while (running.get()) {
                int n = rec.read(buf, 0, frame);
                if (n <= 0) continue;
                total++;
                applyGain(buf, n, g);
                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                double rms = Math.sqrt(sum / n);
                meterSum += rms;
                if (total % 10 == 0) { try { AppCore.get(ctx).micRms = meterSum / 10; } catch (Exception ignored) { } meterSum = 0; }
                if (total <= 5) { noise = noise < 0 ? rms : (noise + rms) / 2; continue; }
                double th = Math.max(noise * 1.6, 180);
                boolean voice = rms > th;
                if (!voice) noise = noise * 0.98 + rms * 0.02;
                byte[] b = toBytes(buf, n);
                if (frames == 0 && !voice) continue; // 말 없는 구간은 버림
                chunk.write(b, 0, b.length);
                frames++;
                if (voice) { speech++; silent = 0; } else silent++;
                boolean cut = (frames >= 15 && silent >= 3) || frames >= 45 || silent >= 10; // 0.3초 쉼 또는 최대 4.5초
                if (cut) {
                    if (speech >= 4) sink.onChunk(wav(chunk.toByteArray()));
                    chunk.reset(); frames = 0; speech = 0; silent = 0;
                }
            }
            if (speech >= 6) sink.onChunk(wav(chunk.toByteArray()));
        } catch (Exception e) {
            log.log("연속 녹음 실패: " + e.getMessage());
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
            if (sco) releaseGlassesMic(am);
        }
    }

    /** 블루투스(안경) 마이크로 전환. 성공하면 true. */
    public static boolean useGlassesMic(AudioManager am, GlassesBle.Log log) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        boolean ok = am.setCommunicationDevice(d);
                        if (ok) Thread.sleep(900);
                        return ok;
                    }
                }
                log.log("안경 마이크를 찾지 못해 휴대폰 마이크 사용");
            } else {
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
                Thread.sleep(1200);
                return true;
            }
        } catch (Exception e) { log.log("안경 마이크 전환 실패, 휴대폰 마이크 사용"); }
        return false;
    }

    public static void releaseGlassesMic(AudioManager am) {
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
            else { am.setBluetoothScoOn(false); am.stopBluetoothSco(); }
        } catch (Exception ignored) { }
    }

    public static void beep(boolean sco) {
        try {
            ToneGenerator tg = new ToneGenerator(sco ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC, 90);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 180);
            Thread.sleep(250);
            tg.release();
        } catch (Exception ignored) { }
    }

    /**
     * 녹음: 말이 시작되면 녹음하고, 말이 끝나고 1.2초 조용하면 멈춘다.
     * waitSec 안에 말이 없으면 null.
     */
    @SuppressLint("MissingPermission")
    public static byte[] record(Context ctx, boolean glassesMic, int waitSec, int maxSec, GlassesBle.Log log) {
        try { AppCore.get(ctx).wakeWord.pauseNow(); } catch (Exception ignored) { }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        boolean sco = glassesMic && useGlassesMic(am, log);
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(sco ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            cancel = false;
            beep(sco);
            rec.startRecording();
            log.log((sco ? "안경" : "휴대폰") + " 마이크로 듣는 중… 말씀하세요");

            int frame = RATE / 10; // 100ms
            short[] buf = new short[frame];
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            ByteArrayOutputStream pre = new ByteArrayOutputStream(); // 말 시작 직전 0.5초 보관
            double noise = -1;
            boolean speaking = false;
            int silentFrames = 0, total = 0, speechFrames = 0;
            float g = gain(ctx);
            while (!cancel) {
                int n = rec.read(buf, 0, frame);
                if (n <= 0) continue;
                total++;
                applyGain(buf, n, g);
                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                double rms = Math.sqrt(sum / n);
                if (total % 5 == 0) { try { AppCore.get(ctx).micRms = rms; } catch (Exception ignored) { } }
                byte[] bytes = toBytes(buf, n);
                if (total <= 3) { noise = noise < 0 ? rms : (noise + rms) / 2; pre.write(bytes, 0, bytes.length); continue; }
                double th = Math.max(noise * 2.0, 250);
                if (!speaking) {
                    pre.write(bytes, 0, bytes.length);
                    byte[] p = pre.toByteArray();
                    if (p.length > RATE) { pre.reset(); pre.write(p, p.length - RATE, RATE); } // 0.5초분 유지
                    if (rms > th) { speaking = true; pcm.write(pre.toByteArray(), 0, pre.size()); speechFrames = 1; }
                    else { noise = noise * 0.95 + rms * 0.05; if (total > waitSec * 10) break; }
                } else {
                    pcm.write(bytes, 0, bytes.length);
                    if (rms > th) { silentFrames = 0; speechFrames++; } else silentFrames++;
                    if (silentFrames >= 12 || total > maxSec * 10) break;
                }
            }
            if (!speaking || speechFrames < 3) { log.log("말소리가 들리지 않았어요"); return null; }
            log.log("녹음 완료 (" + String.format("%.1f", pcm.size() / 2.0 / RATE) + "초)");
            return wav(pcm.toByteArray());
        } catch (Exception e) {
            log.log("녹음 실패: " + e.getMessage());
            return null;
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
            if (sco) releaseGlassesMic(am);
        }
    }

    static byte[] toBytes(short[] s, int n) {
        ByteBuffer bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) bb.putShort(s[i]);
        return bb.array();
    }

    static byte[] wav(byte[] pcm) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(pcm.length);
        byte[] out = new byte[44 + pcm.length];
        System.arraycopy(h.array(), 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }
}
