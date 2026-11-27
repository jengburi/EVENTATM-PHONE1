package com.jengburi.glassesai;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/** 화면이 꺼져 있거나 다른 앱을 보고 있어도 반지 리모컨 버튼을 받는 접근성 서비스. */
public class RemoteService extends AccessibilityService {
    public static volatile boolean running;
    private final Set<Integer> consumed = new HashSet<>();

    private MouseGesture mouse;

    @Override protected void onServiceConnected() {
        running = true;
        mouse = new MouseGesture(this);
        AppCore.get(this).log("🎮 리모컨 받기 켜짐 (접근성)");
        applyMouse();
    }

    /** 커서 방식 리모컨(BLE-M3 등): 안드로이드 14 이상에서 마우스 움직임을 가로채서 방향 버튼으로 */
    public void applyMouse() {
        if (android.os.Build.VERSION.SDK_INT < 34) return;
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            boolean on = RemoteKeys.enabled(AppCore.get(this)) && AppCore.get(this).prefs.getBoolean("remoteMouse", true);
            info.setMotionEventSources(on ? android.view.InputDevice.SOURCE_MOUSE : 0);
            setServiceInfo(info);
            if (on) AppCore.get(this).log("🎮 커서 방식 리모컨도 받는 중 (커서 대신 버튼으로 동작)");
        } catch (Throwable t) { AppCore.get(this).log("커서 가로채기 실패: " + t.getMessage()); }
    }

    @Override public void onMotionEvent(android.view.MotionEvent e) {
        if (mouse != null) mouse.feed(e);
    }

    public static volatile RemoteService inst;
    { inst = this; }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) return consumed.contains(code);
            boolean used = RemoteKeys.onKey(this, code);
            if (used) consumed.add(code); else consumed.remove(code);
            return used;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) return consumed.remove(code);
        return false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { running = false; super.onDestroy(); }
}
