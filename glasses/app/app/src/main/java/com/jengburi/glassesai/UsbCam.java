package com.jengburi.glassesai;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCParam;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * USB(OTG) 카메라 — 내시경 카메라, USB 웹캠 등 UVC 카메라에서 사진 한 장을 찍어 JPEG 로 돌려준다.
 */
public class UsbCam {

    /** 꽂혀 있는 UVC(영상) 장치 찾기 */
    static UsbDevice find(Context ctx) {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null) return null;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (d.getDeviceClass() == 14) return d;
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface it = d.getInterface(i);
                if (it.getInterfaceClass() == 14) return d; // 14 = 비디오
            }
            if (d.getDeviceClass() == 239) return d; // 복합 장치(대부분의 웹캠)
        }
        return null;
    }

    public static boolean connected(Context ctx) { return find(ctx) != null; }

    /** 사진 한 장 찍기 (처음 몇 장은 밝기 조절 때문에 버림) */
    public static byte[] capture(Context ctx, GlassesBle.Log log) throws Exception {
        UsbDevice dev = find(ctx);
        if (dev == null) throw new Exception("USB 카메라가 꽂혀 있지 않아요. 내시경·웹캠을 휴대폰에 꽂아 주세요");
        HandlerThread ht = new HandlerThread("usbcam");
        ht.start();
        Handler h = new Handler(ht.getLooper());
        CountDownLatch done = new CountDownLatch(1);
        final byte[][] out = {null};
        final String[] err = {null};
        final UVCCamera[] cam = {null};
        final SurfaceTexture[] tex = {null};
        final USBMonitor[] mon = {null};

        USBMonitor.OnDeviceConnectListener l = new USBMonitor.OnDeviceConnectListener() {
            @Override public void onAttach(UsbDevice d) { }
            @Override public void onDetach(UsbDevice d) { }
            @Override public void onCancel(UsbDevice d) { err[0] = "USB 카메라 사용을 허용하지 않았어요"; done.countDown(); }
            @Override public void onDeviceClose(UsbDevice d, USBMonitor.UsbControlBlock b) { }
            @Override public void onDeviceOpen(UsbDevice d, USBMonitor.UsbControlBlock block, boolean createNew) {
                try {
                    UVCCamera c = new UVCCamera(new UVCParam());
                    cam[0] = c;
                    int r = c.open(block);
                    if (r != 0) throw new Exception("카메라 열기 실패 (" + r + ")");
                    Size s = pick(c.getSupportedSizeList());
                    if (s != null) c.setPreviewSize(s);
                    Size ps = c.getPreviewSize();
                    final int w = ps != null ? ps.width : 640, hgt = ps != null ? ps.height : 480;
                    log.log("USB 카메라: " + w + "×" + hgt);
                    tex[0] = new SurfaceTexture(0);
                    tex[0].setDefaultBufferSize(w, hgt);
                    c.setPreviewTexture(tex[0]);
                    final int[] n = {0};
                    c.setFrameCallback(new IFrameCallback() {
                        @Override public void onFrame(ByteBuffer frame) {
                            if (out[0] != null || ++n[0] < 12) return; // 밝기 맞출 시간
                            try {
                                byte[] nv21 = new byte[frame.remaining()];
                                frame.get(nv21);
                                YuvImage y = new YuvImage(nv21, ImageFormat.NV21, w, hgt, null);
                                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                                y.compressToJpeg(new Rect(0, 0, w, hgt), 90, bo);
                                out[0] = bo.toByteArray();
                            } catch (Exception e) { err[0] = "사진 변환 실패: " + e.getMessage(); }
                            done.countDown();
                        }
                    }, UVCCamera.PIXEL_FORMAT_NV21);
                    c.startPreview();
                } catch (Exception e) {
                    err[0] = e.getMessage();
                    done.countDown();
                }
            }
        };
        try {
            h.post(() -> {
                try {
                    mon[0] = new USBMonitor(ctx, l, h);
                    mon[0].register();
                    if (!mon[0].hasPermission(dev)) log.log("USB 카메라 사용 허용 창이 뜨면 '확인'을 눌러 주세요");
                    mon[0].requestPermission(dev);
                } catch (Exception e) { err[0] = e.getMessage(); done.countDown(); }
            });
            if (!done.await(20, TimeUnit.SECONDS)) throw new Exception("USB 카메라에서 사진이 오지 않아요 (시간 초과)");
            if (out[0] == null) throw new Exception(err[0] != null ? err[0] : "USB 카메라 사진을 못 받았어요");
            return out[0];
        } finally {
            CountDownLatch closed = new CountDownLatch(1);
            h.post(() -> {
                try { if (cam[0] != null) { cam[0].setFrameCallback(null, 0); cam[0].stopPreview(); cam[0].close(); cam[0].destroy(); } } catch (Exception ignored) { }
                try { if (tex[0] != null) tex[0].release(); } catch (Exception ignored) { }
                try { if (mon[0] != null) { mon[0].unregister(); mon[0].destroy(); } } catch (Exception ignored) { }
                closed.countDown();
            });
            closed.await(3, TimeUnit.SECONDS);
            ht.quitSafely();
        }
    }

    /** 1280×720 이하에서 가장 큰 크기 (MJPEG 우선) */
    static Size pick(List<Size> list) {
        if (list == null || list.isEmpty()) return null;
        Size best = null;
        for (Size s : list) {
            if (s.width * s.height > 1280 * 720) continue;
            if (best == null || s.width * s.height > best.width * best.height
                    || (s.width * s.height == best.width * best.height && s.type == UVCCamera.UVC_VS_FRAME_MJPEG)) best = s;
        }
        return best != null ? best : list.get(0);
    }
}
