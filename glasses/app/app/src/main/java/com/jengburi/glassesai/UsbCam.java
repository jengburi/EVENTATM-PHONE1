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

    public static boolean connected(Context ctx) { return find(ctx) != null || externalId(ctx) != null; }

    /** 휴대폰이 USB 카메라를 '외부 카메라'로 잡아 준 경우 그 번호 */
    static String externalId(Context ctx) {
        try {
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (f != null && f == android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL) return id;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 방법 1: 휴대폰 기본 카메라 기능(Camera2)으로 외부 카메라 찍기 */
    @android.annotation.SuppressLint("MissingPermission")
    static byte[] captureCamera2(Context ctx, String id, GlassesBle.Log log) throws Exception {
        android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        android.hardware.camera2.params.StreamConfigurationMap map = cm.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size best = null;
        for (android.util.Size z : map.getOutputSizes(ImageFormat.JPEG)) {
            if ((long) z.getWidth() * z.getHeight() > 1920L * 1080) continue;
            if (best == null || z.getWidth() * z.getHeight() > best.getWidth() * best.getHeight()) best = z;
        }
        if (best == null) best = map.getOutputSizes(ImageFormat.JPEG)[0];
        log.log("USB 카메라(외부 카메라): " + best.getWidth() + "×" + best.getHeight());
        HandlerThread ht = new HandlerThread("cam2");
        ht.start();
        Handler h = new Handler(ht.getLooper());
        android.media.ImageReader reader = android.media.ImageReader.newInstance(best.getWidth(), best.getHeight(), ImageFormat.JPEG, 3);
        CountDownLatch done = new CountDownLatch(1);
        final byte[][] out = {null};
        final String[] err = {null};
        final android.hardware.camera2.CameraDevice[] dev = {null};
        final int[] n = {0};
        reader.setOnImageAvailableListener(r -> {
            android.media.Image img = r.acquireLatestImage();
            if (img == null) return;
            try {
                if (out[0] == null && ++n[0] >= 8) { // 밝기·초점 맞출 시간
                    ByteBuffer b = img.getPlanes()[0].getBuffer();
                    byte[] j = new byte[b.remaining()];
                    b.get(j);
                    out[0] = j;
                    done.countDown();
                }
            } finally { img.close(); }
        }, h);
        try {
            cm.openCamera(id, new android.hardware.camera2.CameraDevice.StateCallback() {
                @Override public void onOpened(android.hardware.camera2.CameraDevice d) {
                    dev[0] = d;
                    try {
                        d.createCaptureSession(java.util.Collections.singletonList(reader.getSurface()),
                                new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(android.hardware.camera2.CameraCaptureSession ses) {
                                        try {
                                            android.hardware.camera2.CaptureRequest.Builder rb = d.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW);
                                            rb.addTarget(reader.getSurface());
                                            ses.setRepeatingRequest(rb.build(), null, h);
                                        } catch (Exception e) { err[0] = e.getMessage(); done.countDown(); }
                                    }
                                    @Override public void onConfigureFailed(android.hardware.camera2.CameraCaptureSession ses) { err[0] = "카메라 설정 실패"; done.countDown(); }
                                }, h);
                    } catch (Exception e) { err[0] = e.getMessage(); done.countDown(); }
                }
                @Override public void onDisconnected(android.hardware.camera2.CameraDevice d) { err[0] = "카메라 연결 끊김"; done.countDown(); }
                @Override public void onError(android.hardware.camera2.CameraDevice d, int e) { err[0] = "카메라 오류 " + e; done.countDown(); }
            }, h);
            if (!done.await(12, TimeUnit.SECONDS)) throw new Exception("외부 카메라에서 사진이 오지 않아요");
            if (out[0] == null) throw new Exception(err[0] != null ? err[0] : "외부 카메라 사진 실패");
            return out[0];
        } finally {
            try { if (dev[0] != null) dev[0].close(); } catch (Exception ignored) { }
            reader.close();
            ht.quitSafely();
        }
    }

    /** 사진 한 장 찍기 (처음 몇 장은 밝기 조절 때문에 버림) */
    public static byte[] capture(Context ctx, GlassesBle.Log log) throws Exception {
        String ext = externalId(ctx);
        if (ext != null) {
            try { return captureCamera2(ctx, ext, log); }
            catch (Exception e) { log.log("외부 카메라 방식 실패: " + e.getMessage() + " → 직접 연결로 다시 시도"); }
        }
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try { return captureUvc(ctx, log, attempt == 1); }
            catch (Exception e) { last = e; log.log("USB 직접 연결 실패: " + e.getMessage() + (attempt == 0 ? " → 작은 화면으로 다시 시도" : "")); }
        }
        throw last;
    }

    /** 방법 2: USB로 직접 연결 (UVC) */
    static byte[] captureUvc(Context ctx, GlassesBle.Log log, boolean small) throws Exception {
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
        final android.media.ImageReader[] sink = {null};
        final byte[][] latest = {null};
        final int[] dims = {640, 480};
        final USBMonitor[] mon = {null};

        USBMonitor.OnDeviceConnectListener l = new USBMonitor.OnDeviceConnectListener() {
            @Override public void onAttach(UsbDevice d) { }
            @Override public void onDetach(UsbDevice d) { }
            @Override public void onCancel(UsbDevice d) { err[0] = "USB 카메라 사용을 허용하지 않았어요"; done.countDown(); }
            @Override public void onDeviceClose(UsbDevice d, USBMonitor.UsbControlBlock b) { }
            @Override public void onDeviceOpen(UsbDevice d, USBMonitor.UsbControlBlock block, boolean createNew) {
                try {
                    // 휴대폰이 먼저 잡고 있으면(-6) 강제로 넘겨받기
                    for (int i = 0; i < d.getInterfaceCount(); i++) {
                        UsbInterface it = d.getInterface(i);
                        if (it.getInterfaceClass() == 14) {
                            try { block.claimInterface(it, true); block.releaseInterface(it); } catch (Exception ignored) { }
                        }
                    }
                    UVCParam prm = new UVCParam();
                    try { prm.setQuirks(UVCCamera.getRecommendedPlatformQuirks()); } catch (Throwable ignored) { }
                    UVCCamera c = new UVCCamera(prm);
                    cam[0] = c;
                    int r = c.open(block);
                    if (r != 0) throw new Exception(r == -6 ? "다른 앱이 USB 카메라를 쓰고 있어요. 웹캠 앱을 완전히 끄고 카메라를 뺐다 다시 꽂아 주세요" : "카메라 열기 실패 (" + r + ")");
                    boolean set = false;
                    int[][] tries = small ? new int[][]{{640, 480}, {320, 240}} : new int[][]{{1280, 720}, {640, 480}};
                    for (int[] t : tries) {
                        try { c.setPreviewSize(t[0], t[1], UVCCamera.FRAME_FORMAT_MJPEG); set = true; break; } catch (Exception ignored) { }
                    }
                    if (!set) {
                        Size s = pick(c.getSupportedSizeList());
                        if (s != null) c.setPreviewSize(s);
                    }
                    Size ps = c.getPreviewSize();
                    final int w = ps != null ? ps.width : 640, hgt = ps != null ? ps.height : 480;
                    dims[0] = w; dims[1] = hgt;
                    log.log("USB 카메라: " + w + "×" + hgt + (small ? " (작게)" : ""));
                    // 화면 대신 영상을 받아서 바로 버려 주는 곳 (안 그러면 몇 장 뒤 멈춤)
                    try {
                        sink[0] = android.media.ImageReader.newInstance(w, hgt, android.graphics.PixelFormat.RGBA_8888, 3);
                        sink[0].setOnImageAvailableListener(r -> {
                            try { android.media.Image im = r.acquireLatestImage(); if (im != null) im.close(); } catch (Throwable ignored) { }
                        }, h);
                        c.setPreviewDisplay(sink[0].getSurface());
                    } catch (Throwable t) {
                        tex[0] = new SurfaceTexture(0);
                        tex[0].setDefaultBufferSize(w, hgt);
                        c.setPreviewTexture(tex[0]);
                    }
                    final int[] n = {0};
                    c.setFrameCallback(new IFrameCallback() {
                        @Override public void onFrame(ByteBuffer frame) {
                            if (out[0] != null) return;
                            byte[] nv21 = new byte[frame.remaining()];
                            frame.get(nv21);
                            latest[0] = nv21;
                            fixDims(dims, nv21.length);
                            if (++n[0] == 1) log.log("USB 카메라 영상 들어옴");
                            if (n[0] < 10) return; // 밝기 맞출 시간
                            try {
                                YuvImage y = new YuvImage(nv21, ImageFormat.NV21, dims[0], dims[1], null);
                                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                                y.compressToJpeg(new Rect(0, 0, dims[0], dims[1]), 90, bo);
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
            if (!done.await(8, TimeUnit.SECONDS)) {
                // 몇 장 받다가 멈췄으면 마지막으로 받은 장면을 사진으로
                byte[] last = latest[0];
                if (last == null) throw new Exception("USB 카메라에서 영상이 하나도 오지 않아요 (시간 초과)");
                YuvImage y = new YuvImage(last, ImageFormat.NV21, dims[0], dims[1], null);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                y.compressToJpeg(new Rect(0, 0, dims[0], dims[1]), 90, bo);
                log.log("USB 카메라: 마지막으로 받은 장면을 사용");
                return bo.toByteArray();
            }
            if (out[0] == null) throw new Exception(err[0] != null ? err[0] : "USB 카메라 사진을 못 받았어요");
            return out[0];
        } finally {
            CountDownLatch closed = new CountDownLatch(1);
            h.post(() -> {
                try { if (cam[0] != null) { cam[0].setFrameCallback(null, 0); cam[0].stopPreview(); cam[0].close(); cam[0].destroy(); } } catch (Exception ignored) { }
                try { if (tex[0] != null) tex[0].release(); } catch (Exception ignored) { }
                try { if (sink[0] != null) sink[0].close(); } catch (Exception ignored) { }
                try { if (mon[0] != null) { mon[0].unregister(); mon[0].destroy(); } } catch (Exception ignored) { }
                closed.countDown();
            });
            closed.await(3, TimeUnit.SECONDS);
            ht.quitSafely();
        }
    }

    /** 실제로 들어온 영상 크기에 맞추기 (NV21 = 가로×세로×1.5) */
    static void fixDims(int[] d, int len) {
        if (d[0] * d[1] * 3 / 2 == len) return;
        int[][] c = {{1920, 1080}, {1280, 720}, {1280, 960}, {1024, 768}, {800, 600}, {640, 480}, {640, 360}, {352, 288}, {320, 240}, {2560, 1440}};
        for (int[] x : c) if (x[0] * x[1] * 3 / 2 == len) { d[0] = x[0]; d[1] = x[1]; return; }
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
