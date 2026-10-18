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

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 701;
    private WebView web;
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
        web.getSettings().setDomStorageEnabled(false);
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
        @android.webkit.JavascriptInterface public void connect() {
            runOnUiThread(new Runnable() { @Override public void run() {
                Intent prep = VpnService.prepare(MainActivity.this);
                if (prep != null) startActivityForResult(prep, VPN_REQUEST);
                else startVpn();
            }});
        }
        @android.webkit.JavascriptInterface public void disconnect() {
            runOnUiThread(new Runnable() { @Override public void run() {
                Intent i = new Intent(MainActivity.this, DnsVpnService.class);
                i.setAction(DnsVpnService.ACTION_STOP);
                startService(i);
            }});
        }
        @android.webkit.JavascriptInterface public String appVersion() { return "1.0.0"; }
    }

    private void startVpn() {
        Intent i = new Intent(this, DnsVpnService.class);
        i.setAction(DnsVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) startVpn();
            else Toast.makeText(this, "برای اتصال باید مجوز VPN را تأیید کنید", Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onDestroy() {
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
