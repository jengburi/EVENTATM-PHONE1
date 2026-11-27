package com.jengburi.glassesai;

import android.content.Context;
import android.view.KeyEvent;

/**
 * 블루투스 반지 리모컨 (위·아래·왼쪽·오른쪽·가운데 5버튼) → 앱 동작.
 * 버튼마다 '배우기'로 실제 신호를 기억하고, 동작을 고를 수 있다.
 */
public class RemoteKeys {
    public static final String[] SLOTS = {"⬅ 왼쪽", "➡ 오른쪽", "⬆ 위", "⬇ 아래", "⏺ 가운데"};
    static final int[] DEFAULT_CODES = {KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER};
    /** 화면에 보이는 이름 → 실제 동작 이름(AppCore.parseAction 용) */
    public static final String[] ACTIONS = {"그냥 물어보기 (AI 부르기)", "찍고 설명", "대화 통역", "듣기 통역", "메뉴판 번역",
            "다시 듣기", "멈추기", "칼로리 경찰", "냉장고 셰프", "주차 기억", "내 차 찾기", "회의 기록", "길안내", "없음"};
    static final String[] ACTION_KEYS = {"그냥물어보기", "사진", "통역", "듣기통역", "번역",
            "다시듣기", "멈추기", "칼로리 경찰", "냉장고 셰프", "주차", "내차", "회의", "길안내", ""};
    static final int[] DEFAULT_ACTS = {0, 2, 5, 6, 1};

    /** 배우기 중인 버튼 칸 (-1 = 아님) */
    public static volatile int learnSlot = -1;
    public interface Listener { void onKey(int slot, int code); }
    public static volatile Listener listener;

    static int code(AppCore c, int slot) { return c.prefs.getInt("rk_code_" + slot, DEFAULT_CODES[slot]); }
    public static int act(AppCore c, int slot) { return c.prefs.getInt("rk_act_" + slot, DEFAULT_ACTS[slot]); }
    public static void setAct(AppCore c, int slot, int act) { c.prefs.edit().putInt("rk_act_" + slot, act).apply(); }
    public static boolean enabled(AppCore c) { return c.prefs.getBoolean("remoteOn", true); }

    public static String codeName(int code) {
        String s = KeyEvent.keyCodeToString(code).replace("KEYCODE_", "");
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return "방향키 왼쪽";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "방향키 오른쪽";
            case KeyEvent.KEYCODE_DPAD_UP: return "방향키 위";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "방향키 아래";
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: return "확인";
            case KeyEvent.KEYCODE_VOLUME_UP: return "볼륨 +";
            case KeyEvent.KEYCODE_VOLUME_DOWN: return "볼륨 -";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "재생/정지";
            case KeyEvent.KEYCODE_MEDIA_NEXT: return "다음 곡";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return "이전 곡";
            case KeyEvent.KEYCODE_PAGE_UP: return "페이지 위";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "페이지 아래";
        }
        return s;
    }

    /** 이 신호를 앱이 처리할지 (처리하면 true → 다른 앱으로 안 넘김) */
    public static boolean onKey(Context ctx, int code) {
        AppCore c = AppCore.get(ctx);
        int ls = learnSlot;
        if (ls >= 0) {
            c.prefs.edit().putInt("rk_code_" + ls, code).apply();
            learnSlot = -1;
            c.log("🎮 리모컨 " + SLOTS[ls] + " = " + codeName(code) + " (" + code + ")");
            Listener l = listener; if (l != null) l.onKey(ls, code);
            return true;
        }
        if (!enabled(c)) return false;
        for (int i = 0; i < SLOTS.length; i++) {
            if (code(c, i) != code) continue;
            int a = act(c, i);
            if (a < 0 || a >= ACTION_KEYS.length || ACTION_KEYS[a].isEmpty()) return false;
            c.log("🎮 리모컨 " + SLOTS[i] + " → " + ACTIONS[a]);
            run(c, ACTION_KEYS[a]);
            return true;
        }
        return false;
    }

    static void run(AppCore c, String key) {
        new Thread(() -> {
            if (key.equals("멈추기")) {
                c.stopSpeaking();
                AudioQ.cancel = true;
                if (c.interp.running) c.interp.stop();
                if (c.features.meetingOn) c.features.stopMeeting();
                return;
            }
            c.action("리모컨", AppCore.parseAction(key));
        }).start();
    }
}
