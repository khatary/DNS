package com.irkhatary.dns;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.lang.reflect.Method;
import rikka.shizuku.Shizuku;

/**
 * Safe PUBG Global save editor executed through the Shizuku shell.
 * Only known IntProperty FPS tags are modified; the original save is backed up
 * and the written bytes are read back and verified before success is reported.
 */
public final class ShizukuFpsBridge {
    public interface Result { void done(boolean ok, String message); }

    private static final String SAVE = "/storage/emulated/0/Android/data/com.tencent.ig/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/Active.sav";
    private static final String BACKUP = SAVE + ".irkhatary.bak";
    private static final String TEMP = "/data/local/tmp/irkhatary_Active.sav";
    private static final byte[] GVAS = new byte[]{'G','V','A','S'};

    private ShizukuFpsBridge() {}

    private static boolean hasShizukuPermission() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) { return false; }
    }

    public static void checkFile(Result callback) {
        if (!Shizuku.pingBinder()) {
            callback.done(false, "Shizuku فعال نیست؛ ابتدا Shizuku را Start کن.");
            return;
        }
        if (!hasShizukuPermission()) {
            callback.done(false, "مجوز Shizuku برای IR_Khatary داده نشده است.");
            return;
        }
        try {
            ensureGameClosed();
            // Verify the real read/write path and temporary-file capability without altering the save.
            runChecked("test -r '" + SAVE + "' && test -w '" + SAVE + "' && test -w '/storage/emulated/0/Android/data/com.tencent.ig/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames' && touch '" + TEMP + "' && rm -f '" + TEMP + "'");
            byte[] data = readSave();
            int fpsLevel = countIntProperty(data, "FPSLevel");
            int battle = countIntProperty(data, "BattleFPS");
            int lobby = countIntProperty(data, "LobbyFPS");
            int city = countIntProperty(data, "MainCityFPS");
            if (fpsLevel == 0 || battle == 0) {
                callback.done(false, "فایل خوانده شد، اما تگ‌های اصلی FPSLevel و BattleFPS در فایل PUBG Global پیدا نشدند؛ هیچ تغییری انجام نشد.");
                return;
            }
            callback.done(true, "دسترسی خواندن و نوشتن تأیید شد. Active.sav خوانده شد و تگ‌های اصلی FPS پیدا شدند (FPSLevel: " + fpsLevel + "، BattleFPS: " + battle + "، LobbyFPS: " + lobby + "، MainCityFPS: " + city + ").");
        } catch (Throwable e) {
            callback.done(false, "خواندن Active.sav ناموفق بود: " + safeMessage(e));
        }
    }

    /** Applies the Flash-only graphics flag independently of FPS; all existing FPS tags remain untouched. */
    public static void applyFlashQuality(Result callback) {
        if (!Shizuku.pingBinder()) { callback.done(false, "Shizuku سرویس فعال نیست؛ Shizuku را اجرا کن."); return; }
        if (!hasShizukuPermission()) { callback.done(false, "مجوز Shizuku داده نشده است؛ ابتدا مجوز را بگیر."); return; }
        boolean backupCreated = false;
        boolean writeAttempted = false;
        try {
            ensureGameClosed();
            byte[] original = readSave();
            if (!isGvas(original)) { callback.done(false, "Active.sav معتبر نیست؛ فایل تغییر نکرد."); return; }
            byte[] patched = original.clone();
            int changed = patchBool(patched, "bFlashOnlyQuality", true);
            if (changed == 0) { callback.done(false, "تگ bFlashOnlyQuality در Active.sav پیدا نشد؛ فایل تغییر نکرد."); return; }
            runChecked("test -r '" + SAVE + "' && test -w '" + SAVE + "' && cp -p '" + SAVE + "' '" + BACKUP + "'");
            backupCreated = true;
            writeAttempted = true;
            writeTemp(patched);
            runChecked("test -s '" + TEMP + "' && cat '" + TEMP + "' > '" + SAVE + "' && sync");
            byte[] readBack = readSave();
            if (!Arrays.equals(patched, readBack)) throw new Exception("بررسی فایل پس از ثبت کیفیت Flash ناموفق بود.");
            runChecked("rm -f '" + TEMP + "'");
            callback.done(true, "پرچم کیفیت Flash در Active.sav پابجی گلوبال ثبت و بررسی شد؛ تنظیمات FPS دست‌نخورده ماند. بکاپ ساخته شد.");
        } catch (Throwable e) {
            String restore = "";
            if (backupCreated && writeAttempted) {
                try {
                    runChecked("cat '" + BACKUP + "' > '" + SAVE + "' && sync");
                    if (!Arrays.equals(readSave(), readBackup())) throw new Exception("بررسی بکاپ ناموفق");
                    restore = " فایل اصلی از بکاپ بازیابی شد.";
                } catch (Throwable restoreError) { restore = " هشدار: بازیابی بکاپ ناموفق بود؛ بکاپ در " + BACKUP + " باقی مانده است."; }
            }
            try { runChecked("rm -f '" + TEMP + "'"); } catch (Throwable ignored) {}
            callback.done(false, "اعمال کیفیت Flash ناموفق بود: " + safeMessage(e) + restore);
        }
    }

    /** Unreal BoolProperty stores its value byte in the property tag after size and array index. */
    private static int patchBool(byte[] data, String name, boolean value) {
        byte[] key = (name + "\0").getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int count = 0;
        for (int i = 0; i <= data.length - key.length - 22; i++) {
            boolean match = true;
            for (int j = 0; j < key.length; j++) if (data[i + j] != key[j]) { match = false; break; }
            if (!match) continue;
            int p = i + key.length;
            if (p + 4 > data.length) continue;
            int typeLen = b.getInt(p);
            if (typeLen != 13 || p + 4 + typeLen + 9 > data.length) continue;
            String type = new String(data, p + 4, typeLen - 1, StandardCharsets.UTF_8);
            if (!"BoolProperty".equals(type)) continue;
            p += 4 + typeLen;
            int size = b.getInt(p); p += 4;
            if (size != 0 || p + 5 > data.length) continue;
            p += 4; // array index
            data[p] = (byte)(value ? 1 : 0);
            count++;
            i = p;
        }
        return count;
    }

    public static void apply(final int level, Result callback) {
        if (level < 1 || level > 7) {
            callback.done(false, "مقدار FPS نامعتبر است.");
            return;
        }
        if (!Shizuku.pingBinder()) {
            callback.done(false, "Shizuku سرویس فعال نیست؛ Shizuku را اجرا کن.");
            return;
        }
        if (!hasShizukuPermission()) {
            callback.done(false, "مجوز Shizuku داده نشده است؛ ابتدا مجوز را بگیر.");
            return;
        }

        boolean backupCreated = false;
        boolean writeAttempted = false;
        try {
            ensureGameClosed();
            byte[] original = readSave();
            if (!isGvas(original)) {
                callback.done(false, "فایل Active.sav معتبر نیست یا قالب آن پشتیبانی نمی‌شود؛ فایل تغییر نکرد.");
                return;
            }

            byte[] patched = original.clone();
            int fpsLevel = patchInt(patched, "FPSLevel", level << 8);
            int battle = patchInt(patched, "BattleFPS", level << 8);
            int lobby = patchInt(patched, "LobbyFPS", level << 8);
            int city = patchInt(patched, "MainCityFPS", level << 8);
            if (fpsLevel == 0 || battle == 0) {
                callback.done(false, "تگ‌های اصلی FPSLevel و BattleFPS پیدا نشدند؛ فایل تغییر نکرد.");
                return;
            }

            runChecked("test -r '" + SAVE + "' && test -w '" + SAVE + "' && cp -p '" + SAVE + "' '" + BACKUP + "'");
            backupCreated = true;

            writeAttempted = true;
            writeTemp(patched);
            runChecked("test -s '" + TEMP + "' && cat '" + TEMP + "' > '" + SAVE + "' && sync");

            byte[] readBack = readSave();
            if (!Arrays.equals(patched, readBack)) {
                throw new Exception("مقایسه فایل پس از نوشتن ناموفق بود؛ نسخه پشتیبان بازگردانده می‌شود.");
            }

            runChecked("rm -f '" + TEMP + "'");
            callback.done(true, "تنظیم FPS در Active.sav ثبت و با خواندن دوباره تأیید شد. نسخه پشتیبان ساخته شد. مقدار درخواستی: " + level + " (enum " + (level << 8) + "). PUBG ممکن است سقف FPS را بر اساس دستگاه محدود کند.");
        } catch (Throwable e) {
            String restore = "";
            if (backupCreated && writeAttempted) {
                try {
                    runChecked("cat '" + BACKUP + "' > '" + SAVE + "' && sync");
                    byte[] restored = readSave();
                    if (!Arrays.equals(restored, readBackup())) throw new Exception("بررسی بکاپ ناموفق");
                    restore = " فایل اصلی از بکاپ بازیابی شد.";
                } catch (Throwable restoreError) {
                    restore = " هشدار: بازیابی بکاپ نیز ناموفق شد؛ بکاپ در مسیر " + BACKUP + " باقی مانده است.";
                }
            }
            try { runChecked("rm -f '" + TEMP + "'"); } catch (Throwable ignored) {}
            callback.done(false, "اعمال FPS ناموفق بود: " + safeMessage(e) + restore);
        }
    }

    private static void ensureGameClosed() throws Exception {
        String pids = new String(runBytes("pidof com.tencent.ig 2>/dev/null || true"), StandardCharsets.UTF_8).trim();
        if (!pids.isEmpty()) throw new Exception("PUBG هنوز در حال اجراست؛ بازی را کامل ببند و دوباره بررسی کن.");
    }

    private static byte[] readSave() throws Exception {
        byte[] data = runBytes("cat '" + SAVE + "'");
        if (!isGvas(data) || data.length < 64) throw new Exception("Active.sav پیدا نشد یا فایل GVAS معتبر نیست.");
        return data;
    }

    private static byte[] readBackup() throws Exception {
        byte[] data = runBytes("cat '" + BACKUP + "'");
        if (!isGvas(data) || data.length < 64) throw new Exception("بکاپ معتبر نیست.");
        return data;
    }

    private static boolean isGvas(byte[] data) {
        return data != null && data.length >= 4
                && data[0] == GVAS[0] && data[1] == GVAS[1]
                && data[2] == GVAS[2] && data[3] == GVAS[3];
    }

    private static int countIntProperty(byte[] data, String name) {
        return scanIntProperty(data, name, 0, false);
    }

    private static int patchInt(byte[] data, String name, int value) {
        return scanIntProperty(data, name, value, true);
    }

    /** Validates the complete Unreal property tag before touching its 4-byte int. */
    private static int scanIntProperty(byte[] data, String name, int value, boolean write) {
        byte[] key = (name + "\0").getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int count = 0;
        for (int i = 0; i <= data.length - key.length - 24; i++) {
            boolean match = true;
            for (int j = 0; j < key.length; j++) {
                if (data[i + j] != key[j]) { match = false; break; }
            }
            if (!match) continue;

            int p = i + key.length;
            if (p + 4 > data.length) continue;
            int typeLen = b.getInt(p);
            if (typeLen != 12 || p + 4 + typeLen + 8 > data.length) continue;
            String type = new String(data, p + 4, typeLen - 1, StandardCharsets.UTF_8);
            if (!"IntProperty".equals(type)) continue;

            p += 4 + typeLen;
            int size = b.getInt(p);
            p += 4;
            if (size != 4 || p + 8 > data.length) continue;
            p += 4; // array index
            if (write) b.putInt(p, value);
            count++;
            i = p + 3;
        }
        return count;
    }

    private static void writeTemp(byte[] data) throws Exception {
        Process p = newProcess("cat > '" + TEMP + "'");
        try (OutputStream out = p.getOutputStream()) { out.write(data); }
        int code = p.waitFor();
        if (code != 0) throw new Exception("نوشتن فایل موقت Shizuku ناموفق بود (exit " + code + ").");
    }

    private static byte[] runBytes(String command) throws Exception {
        Process p = newProcess(command);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        int code = p.waitFor();
        String err = readError(p);
        if (code != 0) throw new Exception(err.isEmpty() ? "دستور Shizuku ناموفق بود (exit " + code + ")." : err);
        return out.toByteArray();
    }

    private static void runChecked(String command) throws Exception {
        Process p = newProcess(command);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buffer = new byte[2048];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        int code = p.waitFor();
        String err = readError(p);
        if (code != 0) {
            String message = err.isEmpty() ? out.toString("UTF-8").trim() : err;
            throw new Exception(message.isEmpty() ? "دستور فایل ناموفق بود (exit " + code + ")." : message);
        }
    }

    private static String readError(Process p) throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (InputStream in = p.getErrorStream()) {
            byte[] buffer = new byte[2048];
            int n;
            while ((n = in.read(buffer)) > 0) err.write(buffer, 0, n);
        }
        return err.toString("UTF-8").trim();
    }

    private static Process newProcess(String command) throws Exception {
        // Shizuku's newProcess is private in API 13; invoke the supported
        // implementation reflectively, as required by the Shizuku API.
        Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        return (Process) method.invoke(null, new String[]{"sh", "-c", command}, null, null);
    }

    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? "خطای دسترسی یا نوشتن فایل" : m;
    }
}
