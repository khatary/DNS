package com.irkhatary.dns;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.Manifest;
import android.view.WindowManager;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 701;
    private WebView web;
    private boolean pageLoaded = false;
    private final ExecutorService scanner = Executors.newFixedThreadPool(3);
    private String pendingPrimary = "1.1.1.1";
    private String pendingSecondary = "1.0.0.1";
    private static final String CHANNEL_ID = "irkhatary_dns_status";
    private static final int NOTIFICATION_ID = 8152;
    private static final int FIRST_NOTIFICATION_PERMISSION_REQUEST = 703;
    private static final int SHIZUKU_REQUEST_CODE = 1200;
    private final java.util.concurrent.ExecutorService gfxExecutor = Executors.newSingleThreadExecutor();
    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
        new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
            @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                if (requestCode != SHIZUKU_REQUEST_CODE) return;
                final boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (web != null) web.evaluateJavascript("window.onShizukuPermissionResult(" + granted + ")", null);
                }});
            }
        };
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (web != null) {
                boolean isConnected = intent.getBooleanExtra("connected", false);
                String js = "window.setVpnState(" + isConnected + "," + intent.getLongExtra("elapsed", 0L) + ")";
                if (Build.VERSION.SDK_INT >= 19) web.evaluateJavascript(js, null);
                else web.loadUrl("javascript:" + js);
                if (getPreferences(MODE_PRIVATE).getBoolean("notifications_enabled", false)) showStatusNotification(isConnected);
            }
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        web = new WebView(this);
        web.setBackgroundColor(0xff050817);
        web.getSettings().setJavaScriptEnabled(true);
        // Keep file access enabled only because the UI is bundled in android_asset;
        // no untrusted remote pages are loaded in this WebView.
        web.getSettings().setDomStorageEnabled(true);
        web.setLongClickable(false);
        web.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { return true; } });
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageLoaded = true;
                view.evaluateJavascript("if(window.syncVpnState){window.syncVpnState();}", null);
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        setContentView(web);
        requestNotificationPermissionOnFirstLaunch();
        web.loadUrl("file:///android_asset/index.html");
        IntentFilter filter = new IntentFilter(DnsVpnService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, filter);
    }


    private void requestNotificationPermissionOnFirstLaunch() {
        if (Build.VERSION.SDK_INT < 33) return;
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        if (prefs.getBoolean("first_notification_permission_prompted", false)) return;
        prefs.edit().putBoolean("first_notification_permission_prompted", true).apply();
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, FIRST_NOTIFICATION_PERMISSION_REQUEST);
        } else {
            prefs.edit().putBoolean("notifications_enabled", true).apply();
        }
    }

    public class Bridge {
        @android.webkit.JavascriptInterface public String shizukuServiceStatus() {
            try { return rikka.shizuku.Shizuku.pingBinder() ? "connected" : "disconnected"; }
            catch (Throwable e) { return "disconnected"; }
        }
        @android.webkit.JavascriptInterface public String getDeviceInfo() {
            try {
                org.json.JSONObject info = new org.json.JSONObject();
                info.put("model", Build.MODEL == null ? "Android device" : Build.MODEL);
                info.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
                android.app.ActivityManager am = (android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                if (am != null) am.getMemoryInfo(mi);
                info.put("ramTotal", mi.totalMem);
                info.put("ramAvailable", mi.availMem);
                android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
                long block = fs.getBlockSizeLong();
                info.put("storageTotal", fs.getBlockCountLong() * block);
                info.put("storageAvailable", fs.getAvailableBlocksLong() * block);
                return info.toString();
            } catch (Throwable e) { return ""; }
        }
        @android.webkit.JavascriptInterface public String shizukuStatus() {
            try {
                if (!rikka.shizuku.Shizuku.pingBinder()) return "unavailable";
                return rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ? "granted" : "denied";
            } catch (Throwable e) { return "unavailable"; }
        }
        @android.webkit.JavascriptInterface public void requestShizuku() {
            runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    if (!rikka.shizuku.Shizuku.pingBinder()) {
                        Intent shizuku = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                        if (shizuku != null) startActivity(shizuku);
                        else startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/")));
                        if (web != null) web.evaluateJavascript("window.onShizukuPermissionResult(false,'unavailable')", null);
                        return;
                    }
                    if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        if (web != null) web.evaluateJavascript("window.onShizukuPermissionResult(true)", null);
                    } else {
                        rikka.shizuku.Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                    }
                } catch (Throwable e) {
                    if (web != null) web.evaluateJavascript("window.onShizukuPermissionResult(false)", null);
                    Toast.makeText(MainActivity.this, "درخواست مجوز Shizuku ناموفق بود", Toast.LENGTH_LONG).show();
                }
            }});
        }
        @android.webkit.JavascriptInterface public String applyPubgFps(final int level) { return applyPubgFps(level, "global"); }
        @android.webkit.JavascriptInterface public String applyPubgFps(final int level, final String version) {
            if (level < 1 || level > 7) return "مقدار FPS نامعتبر است";
            if (shizukuStatus().equals("denied")) return "ابتدا مجوز Shizuku را بده";
            if (!shizukuStatus().equals("granted")) return "Shizuku فعال نیست";
            gfxExecutor.execute(new Runnable() { @Override public void run() {
                ShizukuFpsBridge.apply(level, version, new ShizukuFpsBridge.Result() {
                    @Override public void done(final boolean ok, final String message) {
                        runOnUiThread(new Runnable() { @Override public void run() {
                            if (web != null) web.evaluateJavascript("window.onFpsResult(" + ok + "," + org.json.JSONObject.quote(message) + ")", null);
                        }});
                    }
                });
            }});
            return "pending";
        }
        private byte[] loadQualityProfile(String quality) throws Exception {
            String file;
            if ("flash".equalsIgnoreCase(quality)) file = "ActiveFLASH.sav";
            else if ("smooth".equalsIgnoreCase(quality)) file = "ActiveSMOOTH.sav";
            else if ("balanced".equalsIgnoreCase(quality)) file = "ActiveBALANCED.sav";
            else if ("hd".equalsIgnoreCase(quality)) file = "ActiveHD.sav";
            else if ("hdr".equalsIgnoreCase(quality)) file = "ActiveHDR.sav";
            else if ("ultra".equalsIgnoreCase(quality)) file = "ActiveULTRA.sav";
            else if ("uhd".equalsIgnoreCase(quality)) file = "ActiveUHD.sav";
            else throw new IllegalArgumentException("کیفیت انتخاب‌شده معتبر نیست");
            java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(getAssets().open("PUBG_ActiveSAV_profiles(1).zip"));
            try {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (("profiles/" + file).equals(entry.getName())) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = zip.read(buffer)) != -1) out.write(buffer, 0, n);
                        zip.closeEntry();
                        return out.toByteArray();
                    }
                    zip.closeEntry();
                }
                throw new java.io.FileNotFoundException("profiles/" + file + " در profiles.zip نیست");
            } finally { zip.close(); }
        }
        @android.webkit.JavascriptInterface public String applyPubgRenderQuality(final String version, final String quality) {
            if (shizukuStatus().equals("denied")) return "ابتدا مجوز Shizuku را بده";
            if (!shizukuStatus().equals("granted")) return "Shizuku فعال نیست";
            gfxExecutor.execute(new Runnable() { @Override public void run() {
                try {
                    final byte[] profile = loadQualityProfile(quality);
                    ShizukuFpsBridge.applyRenderQuality(version, quality, profile, new ShizukuFpsBridge.Result() {
                        @Override public void done(final boolean ok, final String message) {
                            runOnUiThread(new Runnable() { @Override public void run() {
                                if (web != null) web.evaluateJavascript("window.onRenderQualityResult(" + ok + "," + org.json.JSONObject.quote(message) + ")", null);
                            }});
                        }
                    });
                } catch (Throwable e) {
                    final String message = "پروفایل کیفیت در برنامه پیدا نشد یا خواندن آن ناموفق بود: " + e.getMessage();
                    runOnUiThread(new Runnable() { @Override public void run() {
                        if (web != null) web.evaluateJavascript("window.onRenderQualityResult(false," + org.json.JSONObject.quote(message) + ")", null);
                    }});
                }
            }});
            return "pending";
        }
        @android.webkit.JavascriptInterface public String applyPubgAudioQuality(String version, String quality) {
            return "این نسخه از Active.sav مقدار قابل‌تأییدی برای کیفیت صدای LOW/MEDIUM/HIGH ندارد؛ برای جلوگیری از خرابی فایل هیچ تغییری انجام نشد.";
        }
        @android.webkit.JavascriptInterface public String applyPubgFlashQuality() { return applyPubgFlashQuality("global"); }
        @android.webkit.JavascriptInterface public String applyPubgFlashQuality(final String version) {
            if (shizukuStatus().equals("denied")) return "ابتدا مجوز Shizuku را بده";
            if (!shizukuStatus().equals("granted")) return "Shizuku فعال نیست";
            gfxExecutor.execute(new Runnable() { @Override public void run() {
                try {
                    final byte[] profile = loadQualityProfile("flash");
                    ShizukuFpsBridge.applyRenderQuality(version, "flash", profile, new ShizukuFpsBridge.Result() {
                        @Override public void done(final boolean ok, final String message) {
                            runOnUiThread(new Runnable() { @Override public void run() {
                                if (web != null) web.evaluateJavascript("window.onFlashQualityResult(" + ok + "," + org.json.JSONObject.quote(message) + ")", null);
                            }});
                        }
                    });
                } catch (Throwable e) {
                    final String message = "پروفایل FLASH در برنامه پیدا نشد یا خواندن آن ناموفق بود: " + e.getMessage();
                    runOnUiThread(new Runnable() { @Override public void run() {
                        if (web != null) web.evaluateJavascript("window.onFlashQualityResult(false," + org.json.JSONObject.quote(message) + ")", null);
                    }});
                }
            }});
            return "pending";
        }
        @android.webkit.JavascriptInterface public boolean pubgInstalled() { return pubgInstalled("global"); }
        @android.webkit.JavascriptInterface public boolean pubgInstalled(final String version) {
            try { getPackageManager().getPackageInfo(ShizukuFpsBridge.packageForVersion(version), 0); return true; }
            catch (PackageManager.NameNotFoundException e) { return false; }
        }
        @android.webkit.JavascriptInterface public String checkPubgSave() { return checkPubgSave("global"); }
        @android.webkit.JavascriptInterface public String checkPubgSave(final String version) {
            if (!shizukuStatus().equals("granted")) return "ابتدا Shizuku را اجرا و مجوز آن را بده.";
            gfxExecutor.execute(new Runnable() { @Override public void run() {
                ShizukuFpsBridge.checkFile(version, new ShizukuFpsBridge.Result() {
                    @Override public void done(final boolean ok, final String message) {
                        runOnUiThread(new Runnable() { @Override public void run() {
                            if (web != null) web.evaluateJavascript("window.onFpsFileCheck(" + ok + "," + org.json.JSONObject.quote(message) + ")", null);
                        }});
                    }
                });
            }});
            return "pending";
        }
        @android.webkit.JavascriptInterface public String getVpnState() {
            boolean active = DnsVpnService.tunnelActive;
            long elapsed = active ? Math.max(0L, System.currentTimeMillis() - DnsVpnService.tunnelStartedAt) : 0L;
            return active + "|" + elapsed + "|" + DnsVpnService.tunnelDns1 + "|" + DnsVpnService.tunnelDns2;
        }
        @android.webkit.JavascriptInterface public void setGameOptimization(final boolean enabled) {
            runOnUiThread(new Runnable() { @Override public void run() {
                getPreferences(MODE_PRIVATE).edit().putBoolean("game_optimization", enabled).apply();
                if (enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }});
        }
        @android.webkit.JavascriptInterface public boolean getNotificationsEnabled() {
            return getPreferences(MODE_PRIVATE).getBoolean("notifications_enabled", false);
        }
        @android.webkit.JavascriptInterface public void setNotificationsEnabled(final boolean enabled) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_enabled", enabled).apply();
            if (enabled && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 702);
                }});
            }
            if (!enabled) {
                NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTIFICATION_ID);
            }
        }
        @android.webkit.JavascriptInterface public void connect(final String primary, final String secondary) {
            runOnUiThread(new Runnable() { @Override public void run() {
                Intent prep = VpnService.prepare(MainActivity.this);
                pendingPrimary = primary; pendingSecondary = secondary;
                if (prep != null) startActivityForResult(prep, VPN_REQUEST);
                else startVpn(primary, secondary);
            }});
        }
        @android.webkit.JavascriptInterface public void disconnect() {
            runOnUiThread(new Runnable() { @Override public void run() {
                Intent i = new Intent(MainActivity.this, DnsVpnService.class);
                i.setAction(DnsVpnService.ACTION_STOP);
                startService(i);
            }});
        }
        @android.webkit.JavascriptInterface public void openFreeVpn() {
            runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    Intent app = getPackageManager().getLaunchIntentForPackage("ch.protonvpn.android");
                    if (app != null) {
                        startActivity(app);
                    } else {
                        Intent webIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://protonvpn.com/free-vpn"));
                        startActivity(webIntent);
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "برای VPN رایگان، Proton VPN را از وب‌سایت رسمی نصب کن", Toast.LENGTH_LONG).show();
                }
            }});
        }
        @android.webkit.JavascriptInterface public String getPubgIcon() {
            try {
                android.graphics.drawable.Drawable d = getPackageManager().getApplicationIcon("com.tencent.ig");
                int size = 144;
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                d.setBounds(0, 0, size, size);
                d.draw(canvas);
                java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream);
                bitmap.recycle();
                return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }
        @android.webkit.JavascriptInterface public void launchPubg() { launchPubg("global"); }
        @android.webkit.JavascriptInterface public void launchPubg(final String version) {
            runOnUiThread(new Runnable() { @Override public void run() {
                final String packageName = ShizukuFpsBridge.packageForVersion(version);
                try {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launch != null) startActivity(launch);
                    else {
                        Intent market = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + packageName));
                        try { startActivity(market); }
                        catch (Exception noStore) { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + packageName))); }
                    }
                } catch (Exception ignored) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + packageName))); }
                    catch (Exception ignoredAgain) {}
                }
            }});
        }
        @android.webkit.JavascriptInterface public void scanDns(final String address) {
            scanner.execute(new Runnable() { @Override public void run() {
                long start = android.os.SystemClock.elapsedRealtime();
                boolean ok = false;
                int latency = -1;
                DatagramSocket socket = null;
                try {
                    InetAddress server = InetAddress.getByName(address);
                    byte[] q = new byte[] {
                        0x49,0x52, 0x01,0x00, 0x00,0x01, 0x00,0x00, 0x00,0x00, 0x00,0x00,
                        0x07,'e','x','a','m','p','l','e',0x03,'c','o','m',0x00, 0x00,0x01, 0x00,0x01
                    };
                    socket = new DatagramSocket();
                    socket.setSoTimeout(900);
                    socket.send(new DatagramPacket(q, q.length, server, 53));
                    byte[] answer = new byte[1500];
                    DatagramPacket reply = new DatagramPacket(answer, answer.length);
                    socket.receive(reply);
                    if (reply.getLength() >= 12 && answer[0] == 0x49 && answer[1] == 0x52
                            && (answer[2] & 0x80) != 0) {
                        ok = true;
                        latency = (int)(android.os.SystemClock.elapsedRealtime() - start);
                    }
                } catch (Exception ignored) {
                    ok = false;
                } finally { if (socket != null) socket.close(); }
                final boolean result = ok;
                final int ms = latency;
                final String addr = address;
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (web != null) web.evaluateJavascript("window.onDnsScanResult(" + JSONObjectQuote(addr) + "," + result + "," + ms + ")", null);
                }});
            }});
        }
        @android.webkit.JavascriptInterface public String appVersion() { return "2.1"; }
        @android.webkit.JavascriptInterface public void openTelegram() { runOnUiThread(new Runnable() { @Override public void run() { try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/IR_Khatary_DNS"))); } catch (Exception ignored) {} }}); }
        @android.webkit.JavascriptInterface public void openUrl(final String url) {
            runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    android.net.Uri uri = android.net.Uri.parse(url);
                    String host = uri.getHost();
                    if (host != null && (host.equals("instagram.com") || host.equals("www.instagram.com") || host.equals("t.me") || host.equals("rubika.ir") || host.equals("www.rubika.ir"))) {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    }
                } catch (Exception ignored) {}
            }});
        }
    }

    private void showStatusNotification(boolean connected) {
        // The foreground DNS service already shows the useful gaming-optimizer notification.
        // Suppress the duplicate generic “DNS connected/disconnected” status notification.
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == FIRST_NOTIFICATION_PERMISSION_REQUEST) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_enabled", granted).apply();
            if (web != null && Build.VERSION.SDK_INT >= 19) web.evaluateJavascript("document.getElementById(\'notifications\').checked=" + granted + ";localStorage.setItem(\'irk_notifications\',\'" + (granted ? "1" : "0") + "\');", null);
        }
        if (requestCode == 702 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (getPreferences(MODE_PRIVATE).getBoolean("notifications_enabled", false)) showStatusNotification(false);
        }
    }

    private String JSONObjectQuote(String value) { return "\"" + value.replace("\\\\", "\\\\\\\\").replace("\"", "\\\\\"") + "\""; }

    private void startVpn(String primary, String secondary) {
        Intent i = new Intent(this, DnsVpnService.class);
        i.setAction(DnsVpnService.ACTION_START);
        i.putExtra(DnsVpnService.EXTRA_DNS1, primary);
        i.putExtra(DnsVpnService.EXTRA_DNS2, secondary);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) startVpn(pendingPrimary, pendingSecondary);
            else Toast.makeText(this, "برای اتصال باید مجوز VPN را تأیید کنید", Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (pageLoaded && web != null) {
            web.evaluateJavascript("if(window.syncVpnState){window.syncVpnState();}if(window.gfxRefresh){window.gfxRefresh();}", null);
        }
    }

    @Override protected void onDestroy() {
        try { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener); } catch (Exception ignored) {}
        gfxExecutor.shutdownNow();
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
