package com.irkhatary.dns;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
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
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private String pendingPrimary = "1.1.1.1";
    private String pendingSecondary = "1.0.0.1";
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (web != null) {
                String js = "window.setVpnState(" + intent.getBooleanExtra("connected", false) + "," + intent.getLongExtra("elapsed", 0L) + ")";
                if (Build.VERSION.SDK_INT >= 19) web.evaluateJavascript(js, null);
                else web.loadUrl("javascript:" + js);
            }
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(0xff050817);
        web.getSettings().setJavaScriptEnabled(true);
        // Keep file access enabled only because the UI is bundled in android_asset;
        // no untrusted remote pages are loaded in this WebView.
        web.getSettings().setDomStorageEnabled(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        IntentFilter filter = new IntentFilter(DnsVpnService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, filter);
    }

    private class Bridge {
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
                    socket.setSoTimeout(1800);
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
        @android.webkit.JavascriptInterface public String appVersion() { return "1.5"; }
        @android.webkit.JavascriptInterface public void openTelegram() { runOnUiThread(new Runnable() { @Override public void run() { try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/IR_Khatary_DNS"))); } catch (Exception ignored) {} }}); }
    }

    private String JSONObjectQuote(String value) { return "\\"" + value.replace("\\\\", "\\\\\\\\").replace("\\"", "\\\\\\"") + "\\""; }\n\n    private void startVpn(String primary, String secondary) {
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
    @Override protected void onDestroy() {
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
