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
 * kept as an offset to Android's clock. An NTP query (or the time of the PC/browser) gives
 * the real time: where the model can set its clock (newer firmware), the app sets it;
 * on the a6000 there is no such function, so the app keeps a correction to Sony's clock and
 * works with the real time itself. Summer time follows the EU rule if switched on.
 *
 * Sony's calendar holds the local time (checked on the a6000);
 * UTC = local − (gmtDiff + summerTimeDiff) minutes.
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

    /** Real time minus Sony's clock (UTC, ms), from the last comparison with a time server */
    private static volatile long corrMs;
    private static volatile boolean corrKnown;
    /** Local time follows the EU summer time rule instead of the camera's setting */
    private static volatile boolean euAuto = true;

    /** Sony's clock as UTC in ms (what ends up in EXIF, converted to UTC) */
    public static long cameraUtc() {
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

    /** Best known real time (UTC, ms): Sony's clock plus the correction from the time server */
    public static long nowUtc() {
        return cameraUtc() + corrMs;
    }

    /** Offset of the local time in minutes: camera zone, summer time by EU rule if switched on */
    private static int localOffset(long utc) {
        if (euAuto && (gmtDiff == 0 || gmtDiff == 60 || gmtDiff == 120)) {
            return gmtDiff + (euSummer(utc) ? 60 : 0);
        }
        return gmtDiff + summerDiff;
    }

    /** Local time zone (fixed offset at this moment) */
    public static TimeZone zone() {
        long u = nowUtc();
        return new SimpleTimeZone(localOffset(u) * 60000, "Ortszeit");
    }

    /** Local calendar with the real time */
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

    private static String zoneText(int z, boolean summer) {
        return "UTC" + (z >= 0 ? "+" : "−") + Math.abs(z / 60) + (z % 60 != 0 ? ":" + Math.abs(z % 60) : "")
                + (summer ? " Sommerzeit" : "");
    }

    /** "14.11.2026 11:42:07 · UTC+1" – the time the app works with */
    public static String describe() {
        long u = nowUtc();
        int z = localOffset(u);
        return format("dd.MM.yyyy HH:mm:ss") + " · " + zoneText(z, z != gmtDiff)
                + (corrKnown ? "" : " (nicht abgeglichen)");
    }

    /** Sony's clock as shown by the camera, with its deviation from the real local time */
    public static String describeCamera() {
        long cam = cameraUtc();
        int cz = gmtDiff + summerDiff;
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.GERMANY);
        f.setTimeZone(new SimpleTimeZone(cz * 60000, "Kamera"));
        String t = f.format(new Date(cam)) + " · " + zoneText(cz, summerDiff != 0);
        if (!corrKnown) {
            return t;
        }
        long dev = cameraDeviation();
        return t + (Math.abs(dev) < 1000 ? " · stimmt" : " · geht " + fmtDev(dev).substring(1)
                + (dev > 0 ? " vor" : " nach"));
    }

    /** How far Sony's local display is off from the real local time (ms, + = ahead) */
    public static long cameraDeviation() {
        long real = nowUtc();
        return (cameraUtc() + (gmtDiff + summerDiff) * 60000L) - (real + localOffset(real) * 60000L);
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
        return u.getTimeInMillis() - (gmtDiff + summerDiff) * 60000L;
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
    private static int set(long trueUtc, long elapsed, int newSummer) throws Exception {
        long now = trueUtc + (SystemClock.elapsedRealtime() - elapsed);
        long next = (now / 1000 + 1) * 1000;
        Thread.sleep(Math.max(0, next - now));
        long target = trueUtc + (SystemClock.elapsedRealtime() - elapsed);
        // The calendar is given in local time, like Sony's clock keeps it
        Calendar u = new GregorianCalendar(UTC);
        u.setTimeInMillis(target + (gmtDiff + (newSummer >= 0 ? newSummer : summerDiff)) * 60000L);
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
        int r = callSet(c, tz);
        Log.info("clock set " + c.year + "-" + c.month + "-" + c.day + " " + c.hour + ":" + c.minute + ":" + c.second
                + " lokal" + (tz != null ? " summer " + newSummer : "") + " via " + setter + " -> " + r);
        known = false;
        calibrate();
        return r;
    }

    /** How the clock is set: "public" (TimeUtil.setCurrentTime), "native" (TimeUtil.NativeSetTime) or "keine" */
    private static String setter;
    private static java.lang.reflect.Method setMethod;

    /** Finds a way to set Sony's clock (by reflection, the a6000 lacks the public method of newer models) */
    static String setter() {
        if (setter == null) {
            try {
                setMethod = TimeUtil.class.getMethod("setCurrentTime", PlainCalendar.class, PlainTimeZone.class);
                setter = "public";
            } catch (Throwable t) {
                try {
                    setMethod = TimeUtil.class.getDeclaredMethod("NativeSetTime", PlainCalendar.class, PlainTimeZone.class);
                    setMethod.setAccessible(true);
                    setter = "native";
                } catch (Throwable t2) {
                    setter = "keine";
                }
            }
            Log.info("clock setter " + setter);
        }
        return setter;
    }

    private static int callSet(PlainCalendar c, PlainTimeZone tz) throws Exception {
        if ("keine".equals(setter())) {
            return -100;
        }
        Object r = java.lang.reflect.Modifier.isStatic(setMethod.getModifiers())
                ? setMethod.invoke(null, c, tz)
                : setMethod.invoke(new TimeUtil(), c, tz);
        return r instanceof Integer ? (Integer) r : 0;
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

        void redraw();
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
        euAuto = autoSummer();
        // The correction from the last run is a good guess until the next comparison
        if (prefs.contains("clock.corr")) {
            corrMs = prefs.getLong("clock.corr", 0);
        }
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
        euAuto = on;
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
                    String r = apply(t.utc, t.elapsed, t.server + ", ±" + Math.max(1, t.rtt / 2) + " ms", manual);
                    lastOk = SystemClock.elapsedRealtime();
                    if (manual || r.startsWith("gestellt")) {
                        host.message("Uhr " + r, false);
                    }
                    host.redraw();
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
            return apply(utcMs, SystemClock.elapsedRealtime(), source, true);
        } catch (Throwable e) {
            Log.error("clock from " + source, e);
            return "Fehler: " + e;
        } finally {
            busy = false;
        }
    }

    /** Compares with the real time and sets the clock if it is off by 1 s or more, or summer time differs */
    private String apply(long trueUtc, long elapsed, String source, boolean manual) throws Exception {
        calibrate();
        long real = trueUtc + (SystemClock.elapsedRealtime() - elapsed);
        long dev = cameraUtc() - real;
        int newSummer = -1;
        if (autoSummer() && (gmtDiff == 0 || gmtDiff == 60 || gmtDiff == 120)) {
            int want = euSummer(real) ? 60 : 0;
            if (want != summerDiff) {
                newSummer = want;
            }
        }
        String devText = fmtDev(dev);
        if ("keine".equals(setter())) {
            // a6000: Sony's clock cannot be set by an app; work with the real time instead
            corrMs = -dev;
            corrKnown = true;
            prefs.edit().putLong("clock.corr", corrMs).apply();
            long show = cameraDeviation();
            state = "abgeglichen um " + format("HH:mm") + " (" + source + "); "
                    + (Math.abs(show) < 1000 ? "Sony-Uhr stimmt"
                    : "Sony-Uhr geht " + fmtDev(show).substring(1) + (show > 0 ? " vor" : " nach")
                    + " – App rechnet mit der richtigen Zeit");
            Log.info("clock " + state + " (corr " + corrMs + " ms)");
            return state;
        }
        if (Math.abs(dev) < 1000 && newSummer < 0) {
            corrMs = 0;
        corrKnown = true;
        state = "stimmt (" + devText + ", " + source + ") um " + format("HH:mm");
            Log.info("clock ok " + dev + " ms, " + source);
            return state;
        }
        if ("native".equals(setter()) && !manual && !prefs.getBoolean("clock.native_ok", false)) {
            state = "Abweichung " + devText + ", bitte einmal von Hand stellen (Prüfung des Weges)";
            return state;
        }
        int r = set(trueUtc, elapsed, newSummer);
        if (r != 0) {
            state = "Kamera hat das Stellen abgelehnt (Code " + r + ")";
            return state;
        }
        long after = cameraUtc() - (trueUtc + (SystemClock.elapsedRealtime() - elapsed));
        if (Math.abs(after) > 2000) {
            state = "gestellt, aber zurückgelesen " + fmtDev(after) + " daneben (" + setter() + ")";
            Log.info("clock " + state);
            return state;
        }
        if ("native".equals(setter())) {
            prefs.edit().putBoolean("clock.native_ok", true).apply();
        }
        corrMs = 0;
        corrKnown = true;
        prefs.edit().putLong("clock.corr", 0).apply();
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
        if (s < 60) {
            return sign + s + " s";
        }
        if (s < 3600) {
            return sign + s / 60 + " min " + s % 60 + " s";
        }
        return sign + s / 3600 + " h " + (s % 3600) / 60 + " min";
    }
}
