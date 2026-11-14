package com.github.ronyx2.omkamera;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;

import com.sony.scalar.sysutil.PlainCalendar;
import com.sony.scalar.sysutil.PlainTimeZone;
import com.sony.scalar.sysutil.TimeUtil;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

/**
 * The camera has two clocks:
 * - Sony's clock with date, time and zone from the camera menu. It is used for EXIF data,
 *   folder and file times. It keeps running on the camera's backup battery.
 * - Android's system clock, which is never set on these cameras and cannot be set by an app.
 *
 * The app therefore takes all wall times (log, timed start, daily window) from Sony's clock,
 * kept as an offset to Android's clock, and can set Sony's clock from an NTP server
 * (like a PC or phone does), including the EU summer time switch.
 *
 * Sony's calendar is in UTC; local time = UTC + gmtDiff + summerTimeDiff (minutes).
 */
public final class CameraClock {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long NTP_1900_TO_1970 = 2208988800L;

    /** Sony's UTC minus System.currentTimeMillis() */
    private static volatile long offsetMs;
    private static volatile int gmtDiff = 60;
    private static volatile int summerDiff;
    private static volatile boolean known;
    private static volatile boolean precise;
    /** Sony's TimeUtil is not available (e.g. on a PC or phone) */
    private static volatile boolean missing;

    public static final String[] SERVERS = {"Router (Gateway)", "pool.ntp.org", "ptbtime1.ptb.de"};

    // ---- reading ----

    /** Current UTC time by the camera's clock, in ms */
    public static long nowUtc() {
        if (!known && !missing) {
            try {
                long u = readUtc();
                offsetMs = u + 500 - System.currentTimeMillis();
                known = true;
            } catch (Throwable t) {
                missing = true;
                return System.currentTimeMillis();
            }
        }
        return System.currentTimeMillis() + offsetMs;
    }

    /** Time zone of the camera (fixed offset including summer time) */
    public static TimeZone zone() {
        nowUtc();
        return new SimpleTimeZone((gmtDiff + summerDiff) * 60000, "Kamera");
    }

    /** Local calendar by the camera's clock */
    public static Calendar now() {
        Calendar c = new GregorianCalendar(zone());
        c.setTimeInMillis(nowUtc());
        return c;
    }

    public static String format(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.GERMANY);
        f.setTimeZone(zone());
        return f.format(new Date(nowUtc()));
    }

    /** "14.11.2026 11:42:07 · UTC+1" with a note on summer time */
    public static String describe() {
        int z = gmtDiff + summerDiff;
        String zs = "UTC" + (z >= 0 ? "+" : "−") + Math.abs(z / 60) + (z % 60 != 0 ? ":" + Math.abs(z % 60) : "");
        return format("dd.MM.yyyy HH:mm:ss") + " · " + zs + (summerDiff != 0 ? " (Sommerzeit)" : "");
    }

    public static int gmtDiff() {
        return gmtDiff;
    }

    public static int summerDiff() {
        return summerDiff;
    }

    /** Sony's clock to the second; also reads the zone */
    private static long readUtc() {
        PlainCalendar c = TimeUtil.getCurrentCalendar();
        PlainTimeZone tz = TimeUtil.getCurrentTimeZone();
        if (tz != null) {
            gmtDiff = tz.gmtDiff;
            summerDiff = tz.summerTimeDiff;
        }
        Calendar u = new GregorianCalendar(UTC);
        u.clear();
        u.set(c.year, c.month - 1, c.day, c.hour, c.minute, c.second);
        return u.getTimeInMillis();
    }

    /**
     * Measures the offset to the millisecond by waiting for Sony's clock to turn to the next
     * second (up to 1.2 s). Runs in the background.
     */
    public static void calibrate() {
        try {
            long first = readUtc();
            long until = SystemClock.elapsedRealtime() + 1200;
            while (SystemClock.elapsedRealtime() < until) {
                long u = readUtc();
                if (u != first) {
                    offsetMs = u - System.currentTimeMillis() + 5;
                    known = true;
                    precise = true;
                    return;
                }
                Thread.sleep(10);
            }
            offsetMs = first + 500 - System.currentTimeMillis();
            known = true;
        } catch (Throwable t) {
            Log.error("clock calibrate", t);
        }
    }

    // ---- setting ----

    /**
     * Sets Sony's clock. trueUtc is the real time at the moment elapsed (SystemClock.elapsedRealtime);
     * the call waits for the next full second so that the error stays below a few ms.
     * newSummer is the new summer time difference in minutes or -1 to leave the zone.
     * Returns 0 if the camera accepted it.
     */
    private static int set(long trueUtc, long elapsed, int newSummer) throws InterruptedException {
        long now = trueUtc + (SystemClock.elapsedRealtime() - elapsed);
        long next = (now / 1000 + 1) * 1000;
        Thread.sleep(Math.max(0, next - now));
        long target = trueUtc + (SystemClock.elapsedRealtime() - elapsed);
        Calendar u = new GregorianCalendar(UTC);
        u.setTimeInMillis(target);
        PlainCalendar c = new PlainCalendar();
        c.year = u.get(Calendar.YEAR);
        c.month = u.get(Calendar.MONTH) + 1;
        c.day = u.get(Calendar.DAY_OF_MONTH);
        c.hour = u.get(Calendar.HOUR_OF_DAY);
        c.minute = u.get(Calendar.MINUTE);
        c.second = u.get(Calendar.SECOND);
        PlainTimeZone tz = null;
        if (newSummer >= 0) {
            tz = new PlainTimeZone();
            tz.gmtDiff = gmtDiff;
            tz.summerTimeDiff = newSummer;
        }
        int r = new TimeUtil().setCurrentTime(c, tz);
        Log.info("clock set " + c.year + "-" + c.month + "-" + c.day + " " + c.hour + ":" + c.minute + ":" + c.second
                + " UTC" + (tz != null ? " summer " + newSummer : "") + " -> " + r);
        known = false;
        calibrate();
        return r;
    }

    /** EU rule: summer time from the last Sunday in March to the last Sunday in October, 01:00 UTC */
    static boolean euSummer(long utc) {
        Calendar c = new GregorianCalendar(UTC);
        c.setTimeInMillis(utc);
        int y = c.get(Calendar.YEAR);
        return utc >= lastSunday(y, Calendar.MARCH) + 3600000L && utc < lastSunday(y, Calendar.OCTOBER) + 3600000L;
    }

    private static long lastSunday(int year, int month) {
        Calendar c = new GregorianCalendar(UTC);
        c.clear();
        c.set(year, month, 1);
        c.add(Calendar.MONTH, 1);
        c.add(Calendar.DAY_OF_MONTH, -1);
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        return c.getTimeInMillis();
    }

    // ---- NTP ----

    /** Result of an NTP query: real UTC at the moment elapsed, round trip and stratum */
    static final class NtpTime {
        long utc;
        long elapsed;
        long rtt;
        int stratum;
        String server;
    }

    /** Simple SNTP query (RFC 4330), as Android does it */
    static NtpTime ntp(String host, int timeoutMs) throws Exception {
        DatagramSocket s = new DatagramSocket();
        try {
            s.setSoTimeout(timeoutMs);
            InetAddress a = InetAddress.getByName(host);
            byte[] b = new byte[48];
            b[0] = 0x1B; // version 3, client
            long reqElapsed = SystemClock.elapsedRealtime();
            long reqWall = System.currentTimeMillis();
            writeTs(b, 40, reqWall);
            s.send(new DatagramPacket(b, b.length, a, 123));
            byte[] r = new byte[48];
            DatagramPacket p = new DatagramPacket(r, r.length);
            s.receive(p);
            long respElapsed = SystemClock.elapsedRealtime();
            int mode = r[0] & 7;
            int stratum = r[1] & 0xff;
            if ((mode != 4 && mode != 5) || stratum == 0 || stratum > 15) {
                throw new Exception("ungültige Antwort (Stratum " + stratum + ")");
            }
            if (Math.abs(readTs(r, 24) - reqWall) > 2) {
                throw new Exception("Antwort passt nicht zur Anfrage");
            }
            long recv = readTs(r, 32);
            long tx = readTs(r, 40);
            long respWall = reqWall + (respElapsed - reqElapsed);
            long offset = ((recv - reqWall) + (tx - respWall)) / 2;
            NtpTime t = new NtpTime();
            t.utc = respWall + offset;
            t.elapsed = respElapsed;
            t.rtt = (respElapsed - reqElapsed) - (tx - recv);
            t.stratum = stratum;
            t.server = host;
            return t;
        } finally {
            s.close();
        }
    }

    private static long readTs(byte[] b, int o) {
        long sec = 0;
        long frac = 0;
        for (int i = 0; i < 4; i++) {
            sec = (sec << 8) | (b[o + i] & 0xff);
            frac = (frac << 8) | (b[o + 4 + i] & 0xff);
        }
        return (sec - NTP_1900_TO_1970) * 1000 + (frac * 1000L >> 32);
    }

    private static void writeTs(byte[] b, int o, long ms) {
        long sec = ms / 1000 + NTP_1900_TO_1970;
        long frac = (ms % 1000) * 0x100000000L / 1000;
        for (int i = 3; i >= 0; i--) {
            b[o + i] = (byte) sec;
            b[o + 4 + i] = (byte) frac;
            sec >>= 8;
            frac >>= 8;
        }
    }

    // ---- automatic setting while the app runs ----

    public interface Host {
        /** WLAN has an address */
        boolean wifiConnected();

        /** Gateway (router) of the WLAN, or null */
        String gateway();

        void message(String text, boolean warn);
    }

    private final SharedPreferences prefs;
    private final Host host;
    private final Handler handler = new Handler();
    private volatile boolean busy;
    private long lastTry = -1;
    private long lastOk = -1;
    private volatile String state = "noch nicht abgeglichen";

    public CameraClock(SharedPreferences prefs, Host host) {
        this.prefs = prefs;
        this.host = host;
    }

    public boolean auto() {
        return prefs.getBoolean("clock.auto", true);
    }

    public void setAuto(boolean on) {
        prefs.edit().putBoolean("clock.auto", on).apply();
        lastTry = -1;
    }

    public boolean autoSummer() {
        return prefs.getBoolean("clock.dst", true);
    }

    public void setAutoSummer(boolean on) {
        prefs.edit().putBoolean("clock.dst", on).apply();
    }

    public int server() {
        int s = prefs.getInt("clock.server", 0);
        return s < 0 || s >= SERVERS.length ? 0 : s;
    }

    public void setServer(int s) {
        prefs.edit().putInt("clock.server", s).apply();
    }

    public String state() {
        return state;
    }

    /** A query or setting is running */
    public boolean busy() {
        return busy;
    }

    public void start() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                calibrate();
            }
        }, "clock").start();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 3000);
    }

    public void stop() {
        handler.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, 15000);
            if (!auto() || busy || !host.wifiConnected()) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            // Once per start of the app, then every 6 hours; after a failure again after 2 minutes
            boolean due = lastOk < 0 ? (lastTry < 0 || now - lastTry > 120000) : now - lastOk > 6 * 3600000L;
            if (due) {
                syncNtp(false);
            }
        }
    };

    /** Queries the NTP server and sets the clock in the background */
    public String syncNtp(final boolean manual) {
        if (busy) {
            return "Abgleich läuft bereits";
        }
        if (!host.wifiConnected()) {
            return "kein WLAN";
        }
        busy = true;
        lastTry = SystemClock.elapsedRealtime();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    NtpTime t = null;
                    Exception err = null;
                    String gw = host.gateway();
                    String[] order = server() == 0
                            ? new String[]{gw, "pool.ntp.org"}
                            : new String[]{SERVERS[server()]};
                    for (String h : order) {
                        if (h == null) {
                            continue;
                        }
                        try {
                            t = ntp(h, 3000);
                            break;
                        } catch (Exception e) {
                            err = e;
                            Log.info("ntp " + h + ": " + e);
                        }
                    }
                    if (t == null) {
                        state = "keine Antwort vom Zeitserver" + (err != null ? " (" + err.getMessage() + ")" : "");
                        if (manual) {
                            host.message("Uhr: " + state, true);
                        }
                        return;
                    }
                    String r = apply(t.utc, t.elapsed, t.server + ", ±" + Math.max(1, t.rtt / 2) + " ms");
                    lastOk = SystemClock.elapsedRealtime();
                    if (manual || r.startsWith("gestellt")) {
                        host.message("Uhr " + r, false);
                    }
                } catch (Throwable e) {
                    state = "Fehler: " + e;
                    Log.error("clock sync", e);
                } finally {
                    busy = false;
                }
            }
        }, "ntp").start();
        return "Zeitserver wird gefragt …";
    }

    /** Sets the clock from a time sent by the PC or browser (UTC ms, taken as "now") */
    public String syncFrom(long utcMs, String source) {
        if (busy) {
            return "Abgleich läuft bereits";
        }
        busy = true;
        try {
            return apply(utcMs, SystemClock.elapsedRealtime(), source);
        } catch (Throwable e) {
            Log.error("clock from " + source, e);
            return "Fehler: " + e;
        } finally {
            busy = false;
        }
    }

    /** Compares with the real time and sets the clock if it is off by 1 s or more, or summer time differs */
    private String apply(long trueUtc, long elapsed, String source) throws InterruptedException {
        calibrate();
        long real = trueUtc + (SystemClock.elapsedRealtime() - elapsed);
        long dev = nowUtc() - real;
        int newSummer = -1;
        if (autoSummer() && (gmtDiff == 0 || gmtDiff == 60 || gmtDiff == 120)) {
            int want = euSummer(real) ? 60 : 0;
            if (want != summerDiff) {
                newSummer = want;
            }
        }
        String devText = fmtDev(dev);
        if (Math.abs(dev) < 1000 && newSummer < 0) {
            state = "stimmt (" + devText + ", " + source + ") um " + format("HH:mm");
            Log.info("clock ok " + dev + " ms, " + source);
            return state;
        }
        int r = set(trueUtc, elapsed, newSummer);
        if (r != 0) {
            state = "Kamera hat das Stellen abgelehnt (Code " + r + ")";
            return state;
        }
        long after = nowUtc() - (trueUtc + (SystemClock.elapsedRealtime() - elapsed));
        state = "gestellt um " + format("HH:mm") + ": war " + devText
                + (newSummer >= 0 ? (newSummer > 0 ? ", Sommerzeit ein" : ", Sommerzeit aus") : "")
                + ", jetzt " + fmtDev(after) + " (" + source + ")";
        Log.info("clock " + state);
        return state;
    }

    private static String fmtDev(long ms) {
        long a = Math.abs(ms);
        String sign = ms > 0 ? "+" : ms < 0 ? "−" : "±";
        if (a < 10000) {
            return sign + String.format(Locale.GERMANY, "%.1f s", a / 1000.0);
        }
        long s = Math.round(a / 1000.0);
        if (s < 3600) {
            return sign + s / 60 + " min " + s % 60 + " s";
        }
        return sign + s / 3600 + " h " + (s % 3600) / 60 + " min";
    }
}
