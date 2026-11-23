package com.irkhatary.dns

// نمونه‌ی سمت اندروید برای پل JS (AndroidApp) — تست نشده؛ با پروژه‌ی خودت تطبیق بده.
// وابستگی‌ها: dev.rikka.shizuku:api و provider  (نسخه ۱۳+)
// AndroidManifest: <provider android:name="rikka.shizuku.ShizukuProvider" android:authorities="${applicationId}.shizuku" android:exported="true" android:multiprocess="false" android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"/>
import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShizukuFpsBridge(private val runJs: (String) -> Unit) {
    private val savePath = "/storage/emulated/0/Android/data/com.tencent.ig/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/Active.sav"

    @JavascriptInterface fun shizukuStatus(): String = try {
        if (!Shizuku.pingBinder()) "unavailable"
        else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "granted" else "denied"
    } catch (e: Throwable) { "unavailable" }

    @JavascriptInterface fun requestShizuku() { try { Shizuku.requestPermission(1001) } catch (_: Throwable) {} }

    // برمی‌گرداند "ok" یا متن خطا
    @JavascriptInterface fun applyPubgFps(level: Int): String = try {
        val data = sh("cat '$savePath'")
        if (data.size < 100 || String(data, 0, 4) != "GVAS") "فایل Active.sav پیدا نشد"
        else {
            sh("cp '$savePath' '$savePath.bak'")
            for (name in listOf("FPSLevel", "BattleFPS", "LobbyFPS", "MainCityFPS")) patchInt(data, name, level)
            val tmp = "/data/local/tmp/Active.sav"
            shWrite(tmp, data); sh("cp $tmp '$savePath'; rm $tmp")
            "ok"
        }
    } catch (e: Throwable) { "خطا: ${e.message}" }

    // GVAS: name(FString) type(FString) size(int64) 1 بایت صفر، سپس مقدار int32
    private fun patchInt(d: ByteArray, name: String, v: Int) {
        val key = (name + "\u0000").toByteArray(); var i = -1
        outer@ for (s in 0..d.size - key.size) { for (j in key.indices) if (d[s + j] != key[j]) continue@outer; i = s; break }
        if (i < 0) return
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        var p = i + key.size; val tl = bb.getInt(p); p += 4 + tl; p += 8 + 1
        bb.putInt(p, v)
    }
    private fun sh(cmd: String): ByteArray {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java).apply { isAccessible = true }
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = ByteArrayOutputStream(); p.inputStream.copyTo(out); p.waitFor(); return out.toByteArray()
    }
    private fun shWrite(path: String, data: ByteArray) {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java).apply { isAccessible = true }
        val p = m.invoke(null, arrayOf("sh", "-c", "cat > $path"), null, null) as Process
        p.outputStream.use { it.write(data) }; p.waitFor()
    }
}
// ثبت: webView.addJavascriptInterface(ShizukuFpsBridge { js -> webView.post { webView.evaluateJavascript(js, null) } }, "AndroidApp")
// اگر از قبل شیء AndroidApp داری، این متدها را به همان کلاس اضافه کن (یک نام فقط یک بار می‌تواند ثبت شود).