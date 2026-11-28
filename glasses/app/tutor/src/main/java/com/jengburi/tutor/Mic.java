package com.jengburi.tutor;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 휴대폰 마이크로 질문 한 마디 녹음 (말이 끝나면 자동으로 멈춤) → WAV */
public class Mic {
    static final int RATE = 16000;
    public static volatile boolean cancel;

    @SuppressLint("MissingPermission")
    public static byte[] record(int waitSec, int maxSec, LogSink log) {
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            cancel = false;
            try { ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 80); tg.startTone(ToneGenerator.TONE_PROP_BEEP, 160); Thread.sleep(220); tg.release(); } catch (Exception ignored) { }
            rec.startRecording();
            log.log("🎤 듣는 중… 말씀하세요");
            short[] buf = new short[RATE / 10];
            ByteArrayOutputStream pcm = new ByteArrayOutputStream(), pre = new ByteArrayOutputStream();
            double noise = -1;
            boolean speaking = false;
            int total = 0, silent = 0, speech = 0;
            while (!cancel) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                total++;
                for (int i = 0; i < n; i++) { float v = buf[i] * 2f; buf[i] = (short) Math.max(-32768, Math.min(32767, v)); }
                double sum = 0; for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                double rms = Math.sqrt(sum / n);
                byte[] b = bytes(buf, n);
                if (total <= 3) { noise = noise < 0 ? rms : (noise + rms) / 2; pre.write(b, 0, b.length); continue; }
                double th = Math.max(noise * 2.0, 250);
                if (!speaking) {
                    pre.write(b, 0, b.length);
                    byte[] p = pre.toByteArray();
                    if (p.length > RATE) { pre.reset(); pre.write(p, p.length - RATE, RATE); }
                    if (rms > th) { speaking = true; pcm.write(pre.toByteArray(), 0, pre.size()); speech = 1; }
                    else { noise = noise * 0.95 + rms * 0.05; if (total > waitSec * 10) break; }
                } else {
                    pcm.write(b, 0, b.length);
                    if (rms > th) { silent = 0; speech++; } else silent++;
                    if (silent >= 12 || total > maxSec * 10) break;
                }
            }
            if (!speaking || speech < 3) { log.log("말소리가 들리지 않았어요"); return null; }
            return wav(pcm.toByteArray());
        } catch (Exception e) {
            log.log("녹음 실패: " + e.getMessage());
            return null;
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
        }
    }

    static byte[] bytes(short[] s, int n) {
        ByteBuffer bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) bb.putShort(s[i]);
        return bb.array();
    }

    static byte[] wav(byte[] pcm) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(pcm.length);
        byte[] out = new byte[44 + pcm.length];
        System.arraycopy(h.array(), 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }
}
