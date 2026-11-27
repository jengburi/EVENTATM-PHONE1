package com.jengburi.glassesai;

import android.content.Context;

/** 공유용 버전: USB 카메라 기능 없음 */
public class UsbCam {
    public static boolean connected(Context ctx) { return false; }
    public static byte[] capture(Context ctx, GlassesBle.Log log) throws Exception {
        throw new Exception("이 버전은 USB 카메라를 지원하지 않아요");
    }
}
