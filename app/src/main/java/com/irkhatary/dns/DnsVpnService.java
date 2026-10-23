package com.irkhatary.dns;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.content.Intent;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNS-only VPN prototype. It forwards IPv4 UDP DNS queries to the configured resolvers.
 * This is not a full-device VPN: non-DNS traffic is intentionally not routed.
 */
public class DnsVpnService extends VpnService {
    public static final String ACTION_START = "com.irkhatary.dns.START";
    public static final String ACTION_STOP = "com.irkhatary.dns.STOP";
    public static final String ACTION_STATE = "com.irkhatary.dns.STATE";
    public static final String EXTRA_DNS1 = "dns1";
    public static final String EXTRA_DNS2 = "dns2";
    private String activeDns1 = "1.1.1.1";
    private String activeDns2 = "1.0.0.1";
    private static final String CHANNEL = "irkhatary_dns";
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ParcelFileDescriptor tun;
    private Thread worker;
    private long startedAt;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTunnel();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running.get()) {
            activeDns1 = validDns(intent == null ? null : intent.getStringExtra(EXTRA_DNS1), "1.1.1.1");
            activeDns2 = validDns(intent == null ? null : intent.getStringExtra(EXTRA_DNS2), "1.0.0.1");
            startTunnel();
        }
        return START_STICKY;
    }

    private String validDns(String value, String fallback) {
        if (value == null || !value.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")) return fallback;
        try { for (String part : value.split("\\.")) if (Integer.parseInt(part) > 255) return fallback; return value; }
        catch (Exception ignored) { return fallback; }
    }

    private void startTunnel() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "IR_Khatary DNS 1.3", NotificationManager.IMPORTANCE_LOW);
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            nb.setContentTitle("IR_Khatary DNS")
              .setContentText("DNS Cloudflare فعال است")
              .setSmallIcon(android.R.drawable.stat_sys_upload_done)
              .setContentIntent(pi)
              .setOngoing(true);
            startForeground(4107, nb.build());

            Builder b = new Builder().setSession("IR_Khatary DNS")
                    .addAddress("10.7.0.2", 32)
                    .addDnsServer(activeDns1).addDnsServer(activeDns2)
                    .addRoute(activeDns1, 32).addRoute(activeDns2, 32)
                    .setMtu(1500);
            tun = b.establish();
            if (tun == null) throw new IllegalStateException("VPN interface could not be established");
            running.set(true);
            startedAt = System.currentTimeMillis();
            broadcastState(true);
            worker = new Thread(new Runnable() { @Override public void run() { readPackets(); } }, "IRK-DNS-TUN");
            worker.start();
        } catch (Exception e) {
            stopTunnel();
            broadcastState(false);
            stopSelf();
        }
    }

    private void readPackets() {
        byte[] buf = new byte[32767];
        try {
            FileInputStream in = new FileInputStream(tun.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
            while (running.get()) {
                int n = in.read(buf);
                if (n <= 0) continue;
                byte[] packet = new byte[n];
                System.arraycopy(buf, 0, packet, 0, n);
                byte[] response = handleDns(packet);
                if (response != null && running.get()) out.write(response);
            }
        } catch (Exception ignored) {
            // The TUN descriptor is closed during normal shutdown.
        }
        if (running.get()) {
            running.set(false);
            broadcastState(false);
            stopSelf();
        }
    }

    private byte[] handleDns(byte[] p) {
        try {
            if (p.length < 28 || ((p[0] >> 4) & 15) != 4 || (p[9] & 255) != 17) return null;
            int ihl = (p[0] & 15) * 4;
            if (ihl < 20 || p.length < ihl + 8) return null;
            int srcPort = u16(p, ihl);
            int dstPort = u16(p, ihl + 2);
            int udpLen = u16(p, ihl + 4);
            if (dstPort != 53 || udpLen < 8 || ihl + udpLen > p.length) return null;
            String target = ip(p, 16);
            if (!activeDns1.equals(target) && !activeDns2.equals(target)) return null;
            byte[] query = new byte[udpLen - 8];
            System.arraycopy(p, ihl + 8, query, 0, query.length);
            DatagramSocket s = new DatagramSocket();
            try {
                if (!protect(s)) return null;
                s.setSoTimeout(2500);
                InetAddress server = InetAddress.getByName(target);
                s.send(new DatagramPacket(query, query.length, server, 53));
                byte[] ans = new byte[4096];
                DatagramPacket reply = new DatagramPacket(ans, ans.length);
                s.receive(reply);
                byte[] dns = new byte[reply.getLength()];
                System.arraycopy(ans, 0, dns, 0, dns.length);
                return makePacket(p, srcPort, dns);
            } finally {
                s.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] makePacket(byte[] original, int srcPort, byte[] dns) {
        int total = 20 + 8 + dns.length;
        byte[] r = new byte[total];
        r[0] = 0x45;
        put16(r, 2, total);
        put16(r, 4, (int)(System.nanoTime() & 0xffff));
        put16(r, 6, 0x4000);
        r[8] = 64;
        r[9] = 17;
        System.arraycopy(original, 16, r, 12, 4);
        System.arraycopy(original, 12, r, 16, 4);
        put16(r, 10, checksum(r, 0, 20));
        put16(r, 20, 53);
        put16(r, 22, srcPort);
        put16(r, 24, 8 + dns.length);
        put16(r, 26, 0);
        System.arraycopy(dns, 0, r, 28, dns.length);
        int c = udpChecksum(r);
        put16(r, 26, c == 0 ? 0xffff : c);
        return r;
    }

    private int udpChecksum(byte[] p) {
        long sum = 0;
        for (int i = 12; i < 20; i += 2) sum += u16(p, i);
        sum += 17;
        sum += u16(p, 24);
        for (int i = 20; i < p.length; i += 2)
            sum += ((p[i] & 255) << 8) | (i + 1 < p.length ? p[i + 1] & 255 : 0);
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return (int)(~sum) & 0xffff;
    }

    private int checksum(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = off; i < off + len; i += 2)
            sum += ((b[i] & 255) << 8) | (i + 1 < off + len ? b[i + 1] & 255 : 0);
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return (int)(~sum) & 0xffff;
    }

    private int u16(byte[] b, int i) { return ((b[i] & 255) << 8) | (b[i + 1] & 255); }
    private void put16(byte[] b, int i, int v) { b[i] = (byte)(v >> 8); b[i + 1] = (byte)v; }
    private String ip(byte[] b, int i) { return (b[i] & 255) + "." + (b[i+1] & 255) + "." + (b[i+2] & 255) + "." + (b[i+3] & 255); }

    private void stopTunnel() {
        running.set(false);
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        tun = null;
        if (worker != null) { worker.interrupt(); worker = null; }
        stopForeground(true);
        broadcastState(false);
    }

    private void broadcastState(boolean connected) {
        Intent i = new Intent(ACTION_STATE);
        i.setPackage(getPackageName());
        i.putExtra("connected", connected);
        i.putExtra("elapsed", connected ? System.currentTimeMillis() - startedAt : 0L);
        sendBroadcast(i);
    }

    @Override public void onRevoke() { stopTunnel(); stopSelf(); super.onRevoke(); }
    @Override public void onDestroy() { stopTunnel(); super.onDestroy(); }
}
