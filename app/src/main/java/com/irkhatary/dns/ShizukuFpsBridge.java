package com.irkhatary.dns;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import rikka.shizuku.Shizuku;

/** Shizuku shell helper. Only updates verified IntProperty tags and keeps a backup. */
public final class ShizukuFpsBridge {
    public interface Result { void done(boolean ok, String message); }
    private static final String SAVE = "/storage/emulated/0/Android/data/com.tencent.ig/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/Active.sav";
    private static final String TEMP = "/data/local/tmp/irkhatary_Active.sav";
    private ShizukuFpsBridge() {}

    public static void apply(int level, Result callback) {
        if (!Shizuku.pingBinder()) { callback.done(false, "Shizuku سرویس فعال نیست؛ Shizuku را اجرا کن."); return; }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            callback.done(false, "مجوز Shizuku داده نشده است."); return;
        }
        try {
            byte[] data = runBytes(new String[]{"sh", "-c", "cat '" + SAVE + "'"});
            if (data.length < 64 || data[0] != 'G' || data[1] != 'V' || data[2] != 'A' || data[3] != 'S') {
                callback.done(false, "فایل تنظیمات PUBG پیدا نشد یا قالب آن پشتیبانی نمی‌شود."); return;
            }
            int changed = 0;
            for (String name : new String[]{"FPSLevel", "BattleFPS", "LobbyFPS", "MainCityFPS"}) changed += patchInt(data, name, level);
            if (changed == 0) { callback.done(false, "گزینه FPS در فایل این نسخه از PUBG پیدا نشد؛ فایل تغییر نکرد."); return; }
            runChecked(new String[]{"sh", "-c", "cp '" + SAVE + "' '" + SAVE + ".irkhatary.bak' && chmod 600 '" + SAVE + ".irkhatary.bak'"});
            Process writer = Shizuku.newProcess(new String[]{"sh", "-c", "cat > '" + TEMP + "'"}, null, null);
            try (OutputStream out = writer.getOutputStream()) { out.write(data); }
            if (writer.waitFor() != 0) throw new Exception("نوشتن فایل موقت ناموفق بود");
            runChecked(new String[]{"sh", "-c", "cat '" + TEMP + "' > '" + SAVE + "' && rm -f '" + TEMP + "'"});
            callback.done(true, "تنظیم FPS ثبت شد؛ نسخه بازی ممکن است سقف 120 را پشتیبانی نکند. ورود به PUBG…");
        } catch (Throwable e) {
            callback.done(false, "اعمال تنظیمات ناموفق: " + (e.getMessage() == null ? "خطای Shizuku" : e.getMessage()));
        }
    }

    private static int patchInt(byte[] d, String name, int value) {
        byte[] key = (name + "\\0").getBytes(StandardCharsets.UTF_8);
        int count = 0;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i <= d.length - key.length - 12; i++) {
            boolean match = true;
            for (int j = 0; j < key.length; j++) if (d[i+j] != key[j]) { match = false; break; }
            if (!match) continue;
            int p = i + key.length;
            if (p + 4 > d.length) continue;
            int typeLen = b.getInt(p);
            if (typeLen <= 0 || typeLen > 64 || p + 4 + typeLen + 8 > d.length) continue;
            String type = new String(d, p + 4, typeLen > 0 && d[p+3+typeLen] == 0 ? typeLen-1 : typeLen, StandardCharsets.UTF_8);
            if (!"IntProperty".equals(type)) continue;
            p += 4 + typeLen;
            int size = b.getInt(p); p += 4;
            if (size != 4 || p + 8 > d.length) continue;
            p += 4; // array index
            b.putInt(p, value);
            count++;
            i = p + 3;
        }
        return count;
    }

    private static byte[] runBytes(String[] cmd) throws Exception {
        Process p = Shizuku.newProcess(cmd);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) { byte[] buf = new byte[8192]; int n; while ((n=in.read(buf))>0) out.write(buf,0,n); }
        if (p.waitFor() != 0) throw new Exception("خواندن فایل ذخیره بازی ناموفق بود");
        return out.toByteArray();
    }
    private static void runChecked(String[] cmd) throws Exception {
        Process p = Shizuku.newProcess(cmd, null, null);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (InputStream in = p.getErrorStream()) { byte[] buf = new byte[2048]; int n; while ((n=in.read(buf))>0) err.write(buf,0,n); }
        if (p.waitFor() != 0) throw new Exception(err.toString("UTF-8"));
    }
}
