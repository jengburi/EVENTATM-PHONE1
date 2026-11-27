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

    @Override protected void onServiceConnected() {
        running = true;
        AppCore.get(this).log("🎮 리모컨 받기 켜짐 (접근성)");
    }

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
