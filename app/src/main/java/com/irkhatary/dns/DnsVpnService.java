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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNS-only VPN. It forwards IPv4 and IPv6 UDP DNS queries to the selected resolvers.
 * Other traffic is intentionally not routed through this service.
 */
public class DnsVpnService extends VpnService {
    public static final String ACTION_START = "com.irkhatary.dns.START";
    public static final String ACTION_STOP = "com.irkhatary.dns.STOP";
    public static final String ACTION_STATE = "com.irkhatary.dns.STATE";
    public static final String EXTRA_DNS1 = "dns1";
    public static final String EXTRA_DNS2 = "dns2";
    private String activeDns1 = "208.67.222.222";
    private String activeDns2 = "208.67.220.220";
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
            activeDns1 = validDns(intent == null ? null : intent.getStringExtra(EXTRA_DNS1), "208.67.222.222");
            activeDns2 = validDns(intent == null ? null : intent.getStringExtra(EXTRA_DNS2), "208.67.220.220");
            startTunnel();
        }
        return START_STICKY;
    }

    private String validDns(String value, String fallback) {
        try {
            if (value == null || !(value.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$") || value.matches("^[0-9a-fA-F:]+$"))) return fallback;
            InetAddress parsed = InetAddress.getByName(value);
            if (!(parsed instanceof Inet4Address) && !(parsed instanceof Inet6Address)) return fallback;
            if (value.indexOf(':') < 0 && value.split("\\.").length != 4) return fallback;
            return parsed.getHostAddress() == null ? fallback : value;
        } catch (Exception ignored) { return fallback; }
    }

    private void startTunnel() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "IR_Khatary DNS 1.6", NotificationManager.IMPORTANCE_LOW);
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            Intent stop = new Intent(this, DnsVpnService.class);
            stop.setAction(ACTION_STOP);
            PendingIntent stopPi = PendingIntent.getService(this, 4108, stop,
                    (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0) | PendingIntent.FLAG_UPDATE_CURRENT);
            nb.setContentTitle("IR_Khatary DNS")
              .setContentText("DNS profile is active")
              .setSmallIcon(com.irkhatary.dns.R.drawable.ic_dns_connected)
                .setColor(0xFF00C853)
              .setContentIntent(pi)
              .addAction(android.R.drawable.ic_media_pause, "قطع اتصال", stopPi)
              .setOngoing(true);
            startForeground(4107, nb.build());

            InetAddress dns1 = InetAddress.getByName(activeDns1);
            InetAddress dns2 = InetAddress.getByName(activeDns2);
            Builder b = new Builder().setSession("IR_Khatary DNS")
                    .addAddress("10.7.0.2", 32)
                    .addAddress("fd00:7::2", 128)
                    .addDnsServer(dns1).addDnsServer(dns2)
                    .addRoute(dns1, dns1 instanceof Inet6Address ? 128 : 32)
                    .addRoute(dns2, dns2 instanceof Inet6Address ? 128 : 32)
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
            if (p.length < 1) return null;
            int version = (p[0] >> 4) & 15;
            if (version == 4) return handleIpv4Dns(p);
            if (version == 6) return handleIpv6Dns(p);
            return null;
        } catch (Exception ignored) { return null; }
    }

    private byte[] handleIpv4Dns(byte[] p) {
        try {
            if (p.length < 28 || (p[9] & 255) != 17) return null;
            int ihl = (p[0] & 15) * 4;
            if (ihl < 20 || p.length < ihl + 8) return null;
            int srcPort = u16(p, ihl);
            int dstPort = u16(p, ihl + 2);
            int udpLen = u16(p, ihl + 4);
            if (dstPort != 53 || udpLen < 8 || ihl + udpLen > p.length) return null;
            InetAddress target = InetAddress.getByAddress(copyRange(p, 16, 4));
            if (!isActiveTarget(target)) return null;
            byte[] query = copyRange(p, ihl + 8, udpLen - 8);
            byte[] dns = forwardQuery(query, target);
            return dns == null ? null : makeIpv4Packet(p, srcPort, dns);
        } catch (Exception ignored) { return null; }
    }

    private byte[] handleIpv6Dns(byte[] p) {
        try {
            if (p.length < 48 || (p[6] & 255) != 17) return null; // UDP directly follows the IPv6 header.
            int payloadLen = u16(p, 4);
            if (payloadLen < 8 || 40 + payloadLen > p.length) return null;
            int srcPort = u16(p, 40);
            int dstPort = u16(p, 42);
            int udpLen = u16(p, 44);
            if (dstPort != 53 || udpLen < 8 || udpLen > payloadLen || 40 + udpLen > p.length) return null;
            InetAddress target = InetAddress.getByAddress(copyRange(p, 24, 16));
            if (!(target instanceof Inet6Address) || !isActiveTarget(target)) return null;
            byte[] query = copyRange(p, 48, udpLen - 8);
            byte[] dns = forwardQuery(query, target);
            return dns == null ? null : makeIpv6Packet(p, srcPort, dns);
        } catch (Exception ignored) { return null; }
    }

    private byte[] forwardQuery(byte[] query, InetAddress target) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            if (!protect(s)) return null;
             s.setSoTimeout(800);
            InetAddress[] candidates = new InetAddress[] { target, alternateTarget(target) };
            for (InetAddress candidate : candidates) {
                if (candidate == null) continue;
                try {
                    s.connect(candidate, 53);
                    s.send(new DatagramPacket(query, query.length));
                    byte[] ans = new byte[4096];
                    DatagramPacket reply = new DatagramPacket(ans, ans.length);
                    s.receive(reply);
                    if (reply.getLength() >= 12) {
                        byte[] dns = new byte[reply.getLength()];
                        System.arraycopy(ans, 0, dns, 0, dns.length);
                        return dns;
                    }
                } catch (Exception ignored) {
                    try { s.disconnect(); } catch (Exception ignoredAgain) {}
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (s != null) s.close();
        }
    }

    private InetAddress alternateTarget(InetAddress target) {\n        try {\n            InetAddress first = InetAddress.getByName(activeDns1);\n            InetAddress second = InetAddress.getByName(activeDns2);\n            if (target.equals(first)) return second;\n            if (target.equals(second)) return first;\n            return first;\n        } catch (Exception ignored) { return null; }\n    }\n\n    private boolean isActiveTarget(InetAddress target) {
        try {
            return target.equals(InetAddress.getByName(activeDns1)) || target.equals(InetAddress.getByName(activeDns2));
        } catch (Exception ignored) { return false; }
    }

    private byte[] makeIpv4Packet(byte[] original, int srcPort, byte[] dns) {
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
        int c = udpChecksumIpv4(r);
        put16(r, 26, c == 0 ? 0xffff : c);
        return r;
    }

    private byte[] makeIpv6Packet(byte[] original, int srcPort, byte[] dns) {
        int udpLen = 8 + dns.length;
        byte[] r = new byte[40 + udpLen];
        r[0] = 0x60;
        put16(r, 4, udpLen);
        r[6] = 17;
        r[7] = 64;
        System.arraycopy(original, 24, r, 8, 16);
        System.arraycopy(original, 8, r, 24, 16);
        put16(r, 40, 53);
        put16(r, 42, srcPort);
        put16(r, 44, udpLen);
        put16(r, 46, 0);
        System.arraycopy(dns, 0, r, 48, dns.length);
        put16(r, 46, udpChecksumIpv6(r) == 0 ? 0xffff : udpChecksumIpv6(r));
        return r;
    }

    private int udpChecksumIpv4(byte[] p) {
        long sum = 0;
        for (int i = 12; i < 20; i += 2) sum += u16(p, i);
        sum += 17;
        sum += u16(p, 24);
        for (int i = 20; i < p.length; i += 2)
            sum += ((p[i] & 255) << 8) | (i + 1 < p.length ? p[i + 1] & 255 : 0);
        return finishChecksum(sum);
    }

    private int udpChecksumIpv6(byte[] p) {
        long sum = 0;
        for (int i = 8; i < 40; i += 2) sum += u16(p, i);
        int udpLen = u16(p, 44);
        sum += (udpLen >> 16) & 0xffff;
        sum += udpLen & 0xffff;
        sum += 17;
        for (int i = 40; i < p.length; i += 2)
            sum += ((p[i] & 255) << 8) | (i + 1 < p.length ? p[i + 1] & 255 : 0);
        return finishChecksum(sum);
    }

    private int checksum(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = off; i < off + len; i += 2)
            sum += ((b[i] & 255) << 8) | (i + 1 < off + len ? b[i + 1] & 255 : 0);
        return finishChecksum(sum);
    }

    private int finishChecksum(long sum) {
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return (int)(~sum) & 0xffff;
    }

    private byte[] copyRange(byte[] source, int offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(source, offset, result, 0, length);
        return result;
    }

    private int u16(byte[] b, int i) { return ((b[i] & 255) << 8) | (b[i + 1] & 255); }
    private void put16(byte[] b, int i, int v) { b[i] = (byte)(v >> 8); b[i + 1] = (byte)v; }

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
