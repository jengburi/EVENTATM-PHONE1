package com.jengburi.glassesai;

import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 안경(블루투스) 마이크로 한 문장을 듣고 글자로 바꾼다. 백그라운드 스레드에서 호출. */
public class VoiceInput {
    public static String listen(Context ctx, int timeoutSec, boolean glassesMic, GlassesBle.Log log) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { log.log("이 휴대폰은 음성 인식을 지원하지 않아요"); return null; }
        try { AppCore.get(ctx).wakeWord.pauseNow(); } catch (Exception ignored) { }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        boolean usedBt = glassesMic && useBluetoothMic(am, log);
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch done = new CountDownLatch(1);
        final String[] result = {null};
        final SpeechRecognizer[] holder = {null};
        main.post(() -> {
            try {
                SpeechRecognizer sr = SpeechRecognizer.createSpeechRecognizer(ctx);
                holder[0] = sr;
                sr.setRecognitionListener(new RecognitionListener() {
                    @Override public void onResults(Bundle b) {
                        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (r != null && !r.isEmpty()) result[0] = r.get(0);
                        done.countDown();
                    }
                    @Override public void onError(int e) { log.log("음성 인식 결과 없음 (코드 " + e + ")"); done.countDown(); }
                    @Override public void onReadyForSpeech(Bundle b) { log.log("듣는 중… 말씀하세요"); }
                    @Override public void onBeginningOfSpeech() { }
                    @Override public void onRmsChanged(float v) { }
                    @Override public void onBufferReceived(byte[] b) { }
                    @Override public void onEndOfSpeech() { }
                    @Override public void onPartialResults(Bundle b) { }
                    @Override public void onEvent(int t, Bundle b) { }
                });
                Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
                it.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500);
                sr.startListening(it);
            } catch (Exception e) { log.log("음성 인식 시작 실패: " + e.getMessage()); done.countDown(); }
        });
        try { done.await(timeoutSec, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        main.post(() -> { if (holder[0] != null) { try { holder[0].destroy(); } catch (Exception ignored) { } } });
        if (usedBt) releaseBluetoothMic(am);
        if (result[0] != null) log.log("질문: " + result[0]);
        return result[0];
    }

    private static boolean useBluetoothMic(AudioManager am, GlassesBle.Log log) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        boolean ok = am.setCommunicationDevice(d);
                        if (ok) { Thread.sleep(800); log.log("안경 마이크 사용"); }
                        return ok;
                    }
                }
            } else {
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
                Thread.sleep(1000);
                return true;
            }
        } catch (Exception e) { log.log("안경 마이크 전환 실패, 휴대폰 마이크 사용"); }
        return false;
    }

    private static void releaseBluetoothMic(AudioManager am) {
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
            else { am.setBluetoothScoOn(false); am.stopBluetoothSco(); }
        } catch (Exception ignored) { }
    }
}
