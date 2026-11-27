package com.jengburi.glassesai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

/**
 * 커서(마우스) 방식 반지 리모컨 (예: BLE-M3): 커서 움직임 한 묶음 → 방향 버튼 하나로 바꿈.
 * 클릭 → 가운데, 스크롤 → 위/아래.
 */
public class MouseGesture {
    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private float sx, sy, lastX = Float.NaN, lastY = Float.NaN;
    private long lastFire;
    private final Runnable end = this::finish;

    public MouseGesture(Context ctx) { this.ctx = ctx; }

    public void feed(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_BUTTON_PRESS) {
            h.removeCallbacks(end);
            sx = sy = 0;
            fire(RemoteKeys.TAP);
            return;
        }
        if (a == MotionEvent.ACTION_SCROLL) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL), hz = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (Math.abs(v) >= Math.abs(hz) && v != 0) fire(v > 0 ? RemoteKeys.SWIPE_UP : RemoteKeys.SWIPE_DOWN);
            else if (hz != 0) fire(hz > 0 ? RemoteKeys.SWIPE_RIGHT : RemoteKeys.SWIPE_LEFT);
            return;
        }
        if (a == MotionEvent.ACTION_HOVER_MOVE || a == MotionEvent.ACTION_MOVE) {
            float dx = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X), dy = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
            if (dx == 0 && dy == 0 && !Float.isNaN(lastX)) { dx = e.getX() - lastX; dy = e.getY() - lastY; }
            lastX = e.getX(); lastY = e.getY();
            sx += dx; sy += dy;
            h.removeCallbacks(end);
            h.postDelayed(end, 180); // 움직임이 0.18초 멈추면 한 번 누른 것으로
        }
    }

    private void finish() {
        float ax = Math.abs(sx), ay = Math.abs(sy);
        float dx = sx, dy = sy;
        sx = sy = 0;
        if (ax < 12 && ay < 12) return;
        if (ax > ay) fire(dx < 0 ? RemoteKeys.SWIPE_LEFT : RemoteKeys.SWIPE_RIGHT);
        else fire(dy < 0 ? RemoteKeys.SWIPE_UP : RemoteKeys.SWIPE_DOWN);
    }

    private void fire(int code) {
        long now = System.currentTimeMillis();
        if (now - lastFire < 250) return;
        lastFire = now;
        h.post(() -> RemoteKeys.onKey(ctx, code));
    }
}
