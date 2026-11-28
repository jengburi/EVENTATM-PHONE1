package com.jengburi.tutor;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCParam;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * USB 웹캠(UVC)을 계속 켜 두고 화면에 보여 주면서, 가장 최근 장면을 JPEG 로 꺼내 준다.
 * (쟁부리 AI 안경 앱에서 실제로 잘 되던 '카메라 도우미' 방식)
 */
public class Cam {
    public interface Listener { void onState(String s, boolean ok); }

    private final LogSink log;
    private final Listener listener;
    private final HandlerThread ht = new HandlerThread("tutor-cam");
    private final Handler h;
    private CameraHelper helper;
    private Surface surface;
    private boolean opened, selected;
    private volatile byte[] latest;
    private final int[] dims = {1280, 720};

    public Cam(LogSink log, Listener listener) {
        this.log = log;
        this.listener = listener;
        ht.start();
        h = new Handler(ht.getLooper());
    }

    public boolean isOpen() { return opened && latest != null; }

    public void start() {
        h.post(() -> {
            if (helper != null) return;
            try {
                helper = new CameraHelper();
                helper.setStateCallback(new ICameraHelper.StateCallback() {
                    @Override public void onAttach(UsbDevice d) { if (!selected) { selected = true; helper.selectDevice(d); } }
                    @Override public void onDeviceOpen(UsbDevice d, boolean first) {
                        UVCParam prm = new UVCParam();
                        try { prm.setQuirks(UVCCamera.getRecommendedPlatformQuirks()); } catch (Throwable ignored) { }
                        helper.openCamera(prm);
                    }
                    @Override public void onCameraOpen(UsbDevice d) {
                        opened = true;
                        try {
                            pickSize();
                            Size ps = helper.getPreviewSize();
                            if (ps != null) { dims[0] = ps.width; dims[1] = ps.height; }
                            if (surface != null) helper.addSurface(surface, false);
                            helper.setFrameCallback(frame -> {
                                byte[] nv = new byte[frame.remaining()];
                                frame.get(nv);
                                fixDims(nv.length);
                                latest = nv;
                            }, UVCCamera.PIXEL_FORMAT_NV21);
                            helper.startPreview();
                            listener.onState("📷 웹캠 연결됨 " + dims[0] + "×" + dims[1], true);
                        } catch (Exception e) { listener.onState("웹캠 시작 실패: " + e.getMessage(), false); }
                    }
                    @Override public void onCameraClose(UsbDevice d) { opened = false; latest = null; }
                    @Override public void onDeviceClose(UsbDevice d) { opened = false; }
                    @Override public void onDetach(UsbDevice d) { opened = false; selected = false; latest = null; listener.onState("웹캠이 빠졌어요. 다시 꽂아 주세요", false); }
                    @Override public void onCancel(UsbDevice d) { selected = false; listener.onState("웹캠 사용을 허용해 주세요", false); }
                    @Override public void onError(UsbDevice d, com.herohan.uvcapp.CameraException e) { listener.onState("웹캠 오류 " + e.getCode(), false); }
                });
                h.postDelayed(() -> {
                    if (selected || helper == null) return;
                    List<UsbDevice> l = helper.getDeviceList();
                    if (l != null && !l.isEmpty()) { selected = true; helper.selectDevice(l.get(0)); }
                    else listener.onState("웹캠을 휴대폰에 꽂아 주세요 (USB-C / OTG)", false);
                }, 800);
            } catch (Throwable t) { listener.onState("웹캠 준비 실패: " + t.getMessage(), false); }
        });
    }

    /** 1280×720 MJPEG 우선 (2K 웹캠도 이 크기면 빠르고 글씨도 잘 보여요) */
    private void pickSize() {
        try {
            List<Size> list = helper.getSupportedSizeList();
            if (list == null) return;
            Size best = null;
            for (Size s : list) {
                if ((long) s.width * s.height > 1920L * 1080) continue;
                if (best == null || s.width * s.height > best.width * best.height) best = s;
            }
            if (best != null && (best.width != dims[0] || best.height != dims[1])) helper.setPreviewSize(best);
        } catch (Throwable ignored) { }
    }

    public void setSurface(Surface s) {
        h.post(() -> {
            if (helper != null && opened) {
                try { if (surface != null) helper.removeSurface(surface); } catch (Throwable ignored) { }
                try { if (s != null) helper.addSurface(s, false); } catch (Throwable ignored) { }
            }
            surface = s;
        });
    }

    public void stop() {
        h.post(() -> {
            try { if (helper != null) { helper.setFrameCallback(null, 0); helper.stopPreview(); helper.closeCamera(); helper.release(); } } catch (Throwable ignored) { }
            helper = null; opened = false; selected = false; latest = null;
        });
    }

    /** 지금 보이는 장면을 JPEG 로 (없으면 null) */
    public byte[] snapshot() {
        byte[] nv = latest;
        if (nv == null) return null;
        int w = dims[0], hh = dims[1];
        YuvImage y = new YuvImage(nv, ImageFormat.NV21, w, hh, null);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        y.compressToJpeg(new Rect(0, 0, w, hh), 88, bo);
        return bo.toByteArray();
    }

    private void fixDims(int len) {
        if (dims[0] * dims[1] * 3 / 2 == len) return;
        int[][] c = {{1920, 1080}, {1280, 720}, {1280, 960}, {1024, 768}, {800, 600}, {640, 480}, {640, 360}, {2560, 1440}};
        for (int[] x : c) if (x[0] * x[1] * 3 / 2 == len) { dims[0] = x[0]; dims[1] = x[1]; return; }
    }
}
