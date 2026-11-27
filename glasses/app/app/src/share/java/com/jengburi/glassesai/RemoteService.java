package com.jengburi.glassesai;

/** 공유용 버전: 리모컨(접근성) 기능 없음 */
public class RemoteService {
    public static volatile boolean running = false;
    public static volatile RemoteService inst = null;
    public void applyMouse() { }
}
