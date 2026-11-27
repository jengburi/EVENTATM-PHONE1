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
            "다시 듣기", "멈추기", "칼로리 경찰", "냉장고 셰프", "주차 기억", "내 차 찾기", "회의 기록", "길안내", "사진만 찍기", "영상 찍기/끝", "없음"};
    static final String[] ACTION_KEYS = {"그냥물어보기", "사진", "통역", "듣기통역", "번역",
            "다시듣기", "멈추기", "칼로리 경찰", "냉장고 셰프", "주차", "내차", "회의", "길안내", "사진만", "영상", ""};
    static final int[] DEFAULT_ACTS = {0, 2, 5, 6, 1};

    /** 터치(스와이프) 방식 리모컨용 가짜 신호 번호 */
    public static final int SWIPE_LEFT = 9001, SWIPE_RIGHT = 9002, SWIPE_UP = 9003, SWIPE_DOWN = 9004, TAP = 9005;
    /** 마지막으로 들어온 신호 (화면 확인용) */
    public static volatile String lastSignal = "";

    /** 배우기 중인 버튼 칸 (-1 = 아님) */
    public static volatile int learnSlot = -1;
    public interface Listener { void onKey(int slot, int code); }
    public static volatile Listener listener;

    static int code(AppCore c, int slot) { return c.prefs.getInt("rk_code_" + slot, DEFAULT_CODES[slot]); }
    static final int[] SWIPE_DEFAULTS = {SWIPE_LEFT, SWIPE_RIGHT, SWIPE_UP, SWIPE_DOWN, TAP};
    /** 이 칸에 해당하는 신호인지 (배우기 전이면 방향키와 커서·터치 방향 둘 다 인정) */
    static boolean matches(AppCore c, int slot, int code) {
        if (code(c, slot) == code) return true;
        return !c.prefs.contains("rk_code_" + slot) && SWIPE_DEFAULTS[slot] == code;
    }
    public static int act(AppCore c, int slot) { return c.prefs.getInt("rk_act_" + slot, DEFAULT_ACTS[slot]); }
    public static void setAct(AppCore c, int slot, int act) { c.prefs.edit().putInt("rk_act_" + slot, act).apply(); }
    public static boolean enabled(AppCore c) { return c.prefs.getBoolean("remoteOn", true); }

    public static String codeName(int code) {
        switch (code) {
            case SWIPE_LEFT: return "터치 밀기 ←";
            case SWIPE_RIGHT: return "터치 밀기 →";
            case SWIPE_UP: return "터치 밀기 ↑";
            case SWIPE_DOWN: return "터치 밀기 ↓";
            case TAP: return "터치 누르기";
        }
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
        lastSignal = codeName(code) + " (" + code + ")";
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
            if (!matches(c, i, code)) continue;
            int a = act(c, i);
            if (a < 0 || a >= ACTION_KEYS.length || ACTION_KEYS[a].isEmpty()) return false;
            c.log("🎮 리모컨 " + SLOTS[i] + " → " + ACTIONS[a]);
            run(c, ACTION_KEYS[a]);
            return true;
        }
        return false;
    }

    public static boolean isMapped(AppCore c, int code) {
        if (!enabled(c)) return false;
        for (int i = 0; i < SLOTS.length; i++) {
            if (!matches(c, i, code)) continue;
            int a = act(c, i);
            return a >= 0 && a < ACTION_KEYS.length && !ACTION_KEYS[a].isEmpty();
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
