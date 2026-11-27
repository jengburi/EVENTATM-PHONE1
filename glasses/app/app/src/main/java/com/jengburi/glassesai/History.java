package com.jengburi.glassesai;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 찍은 사진과 질문/대답을 날짜별로 저장하고 불러온다. */
public class History {
    public static class Entry {
        public String id, time, mode, question, answer;
        public File photo;
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "history");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static void save(Context c, byte[] jpeg, String mode, String question, String answer, boolean toGallery) {
        // 사진이 있는 순간은 드라이브에도 (블로그/쇼츠용)
        if (jpeg != null) { try { AppCore.get(c).uploadMoment(jpeg, mode, question, answer); } catch (Exception ignored) { } }
        try {
            String id = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date());
            File d = dir(c);
            if (jpeg != null) {
                try (FileOutputStream o = new FileOutputStream(new File(d, id + ".jpg"))) { o.write(jpeg); }
                if (toGallery) saveToGallery(c, jpeg, "glasses_" + id + ".jpg");
            }
            JSONObject j = new JSONObject()
                    .put("time", new SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(new Date()))
                    .put("mode", mode).put("question", question == null ? "" : question)
                    .put("answer", answer == null ? "" : answer);
            try (FileOutputStream o = new FileOutputStream(new File(d, id + ".json"))) { o.write(j.toString().getBytes("UTF-8")); }
            // 최근 200개만 보관
            File[] all = d.listFiles((f, n) -> n.endsWith(".json"));
            if (all != null && all.length > 200) {
                Arrays.sort(all);
                for (int i = 0; i < all.length - 200; i++) {
                    String base = all[i].getName().replace(".json", "");
                    all[i].delete();
                    new File(d, base + ".jpg").delete();
                }
            }
        } catch (Exception ignored) { }
    }

    static void saveToGallery(Context c, byte[] jpeg, String name) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GlassesAI");
            Uri u = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return;
            try (OutputStream o = c.getContentResolver().openOutputStream(u)) { o.write(jpeg); }
        } catch (Exception ignored) { }
    }

    public static List<Entry> list(Context c, int max) {
        List<Entry> out = new ArrayList<>();
        File d = dir(c);
        File[] all = d.listFiles((f, n) -> n.endsWith(".json"));
        if (all == null) return out;
        Arrays.sort(all, (a, b) -> b.getName().compareTo(a.getName()));
        for (File f : all) {
            if (out.size() >= max) break;
            try {
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                JSONObject j = new JSONObject(new String(b, "UTF-8"));
                Entry e = new Entry();
                e.id = f.getName().replace(".json", "");
                e.time = j.optString("time"); e.mode = j.optString("mode");
                e.question = j.optString("question"); e.answer = j.optString("answer");
                File p = new File(d, e.id + ".jpg");
                e.photo = p.exists() ? p : null;
                out.add(e);
            } catch (Exception ignored) { }
        }
        return out;
    }
}
