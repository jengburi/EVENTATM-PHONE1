package com.jengburi.glassesai;

import android.net.Network;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** M88 안경 FTP(192.168.48.1, bk7258/123456)에서 파일을 받는 아주 작은 FTP 클라이언트. */
public class Ftp implements AutoCloseable {
    static final String HOST = "192.168.48.1", USER = "bk7258", PASS = "123456";
    private final Network net;
    private final Socket ctrl;
    private final BufferedReader r;
    private final OutputStream w;

    public Ftp(Network net) throws Exception {
        this.net = net;
        ctrl = net.getSocketFactory().createSocket(HOST, 21);
        ctrl.setSoTimeout(15000);
        r = new BufferedReader(new InputStreamReader(ctrl.getInputStream(), "UTF-8"));
        w = ctrl.getOutputStream();
        expect(read(), "2");
        String u = cmd("USER " + USER);
        if (u.startsWith("3")) expect(cmd("PASS " + PASS), "2");
        cmd("TYPE I");
    }

    private String read() throws Exception {
        String line = r.readLine();
        if (line == null) throw new Exception("FTP 연결이 끊겼어요");
        if (line.length() >= 4 && line.charAt(3) == '-') {
            String code = line.substring(0, 3);
            String l;
            while ((l = r.readLine()) != null) if (l.startsWith(code + " ")) { line = l; break; }
        }
        return line;
    }

    private String cmd(String c) throws Exception {
        w.write((c + "\r\n").getBytes("UTF-8"));
        w.flush();
        return read();
    }

    private static void expect(String resp, String prefix) throws Exception {
        if (!resp.startsWith(prefix)) throw new Exception("FTP 오류: " + resp);
    }

    private Socket pasv() throws Exception {
        String resp = cmd("PASV");
        Matcher m = Pattern.compile("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").matcher(resp);
        if (!m.find()) throw new Exception("FTP PASV 오류: " + resp);
        int port = Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
        Socket d = net.getSocketFactory().createSocket(HOST, port);
        d.setSoTimeout(30000);
        return d;
    }

    /** 파일 이름 목록 */
    public List<String> list() throws Exception {
        List<String> out = new ArrayList<>();
        for (String c : new String[]{"NLST", "LIST"}) {
            Socket d = pasv();
            String resp = cmd(c);
            if (!resp.startsWith("1") && !resp.startsWith("2")) { d.close(); continue; }
            BufferedReader dr = new BufferedReader(new InputStreamReader(d.getInputStream(), "UTF-8"));
            String line;
            while ((line = dr.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                out.add(parts[parts.length - 1]); // LIST 는 마지막 칸이 이름
            }
            d.close();
            if (resp.startsWith("1")) read();
            if (!out.isEmpty()) break;
        }
        return out;
    }

    public byte[] retr(String name) throws Exception {
        Socket d = pasv();
        String resp = cmd("RETR " + name);
        if (!resp.startsWith("1") && !resp.startsWith("2")) { d.close(); throw new Exception("FTP 받기 실패: " + resp); }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = d.getInputStream()) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        }
        d.close();
        if (resp.startsWith("1")) read();
        return bo.toByteArray();
    }

    public void retrTo(String name, java.io.File dst) throws Exception {
        Socket d = pasv();
        String resp = cmd("RETR " + name);
        if (!resp.startsWith("1") && !resp.startsWith("2")) { d.close(); throw new Exception("FTP 받기 실패: " + resp); }
        try (InputStream in = d.getInputStream(); java.io.FileOutputStream o = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        d.close();
        if (resp.startsWith("1")) read();
    }

    @Override public void close() {
        try { cmd("QUIT"); } catch (Exception ignored) { }
        try { ctrl.close(); } catch (Exception ignored) { }
    }
}
