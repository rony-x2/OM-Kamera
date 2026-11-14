package com.github.ronyx2.omkamera;

import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;

import java.io.File;
import java.util.Locale;

/**
 * Shooting sequences run by the app, modelled on the newer Sony cameras:
 * interval recording (with a time-lapse film from the live view), focus bracketing,
 * an extended exposure bracket and a BULB timer.
 *
 * A full press of the shutter starts the selected sequence, another one stops it.
 * Everything runs on the main thread, driven by a handler; each step waits for the
 * camera's shutter callback before the next one.
 */
public class Sequencer {

    public enum Mode {
        OFF("Aus"), INTERVAL("Intervallaufnahme"), TIMELAPSE("Zeitraffer (LRT)"), FOCUS("Fokusreihe"),
        BRACKET("Belichtungsreihe+"), BULB("BULB-Timer");
        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    public interface Host {
        void redraw();

        void message(String text, boolean warn);

        /** Keeps the camera awake while a sequence runs */
        void keepAwake();

        /** Half press for the focus before a sequence; returns at once, the result comes later */
        void startFocus();

        void cancelFocus();
    }

    /** Camera, lens and settings for the film's INFO block */
    private String[][] filmInfo() {
        StringBuilder c = new StringBuilder();
        try {
            c.append(com.github.ma1co.openmemories.framework.DeviceInfo.getInstance().getModel());
        } catch (Throwable ignored) {
            c.append("Sony");
        }
        TiffOut.Lens l = session.lensForFiles();
        if (l != null) {
            c.append(", Objektiv ").append(l.name);
            if (l.focalMin > 0) {
                c.append(" (").append(Math.round(l.focalMin));
                if (Math.round(l.focalMax) != Math.round(l.focalMin)) {
                    c.append("-").append(Math.round(l.focalMax));
                }
                c.append(" mm");
                if (l.fMin > 0) {
                    c.append(" F").append(String.valueOf(l.fMin).replace(".0", "").replace('.', ','));
                }
                c.append(")");
            }
            float fl = session.focalLength();
            if (fl > 0) {
                c.append(", ").append(Math.round(fl)).append(" mm");
            }
        }
        c.append(", ").append(session.shutterText()).append(", ").append(session.apertureText());
        CameraSession.Params p = session.current();
        String iso = p != null ? Settings.get("iso").currentText(p) : null;
        if (iso != null) {
            c.append(", ISO ").append(iso);
        }
        c.append(", Intervall ").append(get("iv.interval", 5)).append(" s");
        String date = CameraClock.format("yyyy-MM-dd HH:mm");
        return new String[][]{{"ISFT", "OM Kamera"}, {"ICRD", date}, {"ICMT", c.toString()}};
    }

    // Choices for the menu
    public static final int[] START_DELAYS = {0, 5, 10, 30, 60, 300, 600, 1800, 3600};
    public static final int[] INTERVALS = {1, 2, 3, 5, 10, 15, 20, 30, 60, 120, 300, 600};
    public static final int[] COUNTS = {10, 20, 30, 50, 100, 200, 300, 500, 999, 9999};
    public static final int[] FILM_FPS = {0, 24, 25, 30};
    public static final int[] FOCUS_STEPS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    public static final int[] FOCUS_COUNTS = {3, 5, 10, 15, 20, 30, 50, 99};
    public static final int[] FOCUS_PAUSE = {0, 1, 2, 3, 5};
    public static final int[] BRACKET_COUNTS = {3, 5, 7, 9};
    /** Bracket steps in thirds of an EV */
    public static final int[] BRACKET_STEPS = {1, 2, 3, 4, 5, 6, 9};
    public static final int[] BULB_TIMES = {5, 10, 15, 30, 60, 120, 240, 480, 900, 1800, 3600};
    /** Time-lapse: intervals in tenths of a second, like the Pro Timer's 0.1 s steps */
    public static final int[] TL_INTERVALS = {10, 15, 20, 25, 30, 40, 50, 60, 80, 100, 120, 150, 200, 250, 300, 450, 600, 900, 1200};
    public static final int[] TL_COUNTS = {0, 100, 200, 300, 500, 750, 1000, 1500, 2000, 3000, 5000, 9999};
    public static final int[] TL_RAMP_MIN = {5, 10, 15, 20, 30, 45, 60, 90, 120};
    public static final int[] TL_BULB = {0, 30, 45, 60, 90, 120, 180, 240, 300};
    public static final int[] TL_DARK = {1, 2, 3, 5};
    public static final int[] TL_ISO_MAX = {800, 1600, 3200, 6400, 12800};
    public static final int[] TL_ISO_MIN = {100, 200, 400};
    public static final int[] TL_DAYS = {127, 31, 96};

    private final CameraSession session;
    private final SharedPreferences prefs;
    private final Host host;
    private final Handler handler = new Handler();

    private Mode running = Mode.OFF;
    private int done;
    private int total;
    private long nextAt;
    private long startedAt;
    private long bulbEnd;
    private boolean waitingShot;
    private MjpegAvi film;
    private File filmFile;
    // Focus bracket
    private String focusModeBefore;
    // Exposure bracket
    private int[] offsets;
    private int evBase;
    private boolean manual;
    private int shutterApplied;
    private int shutterSign = 1;

    public Sequencer(CameraSession session, SharedPreferences prefs, Host host) {
        this.session = session;
        this.prefs = prefs;
        this.host = host;
    }

    // ---- options ----

    public Mode mode() {
        try {
            return Mode.valueOf(prefs.getString("seq.mode", "OFF"));
        } catch (IllegalArgumentException e) {
            return Mode.OFF;
        }
    }

    public void setMode(Mode m) {
        prefs.edit().putString("seq.mode", m.name()).apply();
    }

    public int get(String key, int def) {
        return prefs.getInt("seq." + key, def);
    }

    public void put(String key, int value) {
        prefs.edit().putInt("seq." + key, value).apply();
    }

    /** Next value of a list option */
    public void cycle(String key, int[] values, int def) {
        int cur = get(key, def);
        int i = 0;
        while (i < values.length && values[i] != cur) {
            i++;
        }
        prefs.edit().putInt("seq." + key, values[(i + 1) % values.length]).apply();
    }

    public static String duration(int s) {
        if (s == 0) {
            return "sofort";
        }
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return s % 60 == 0 ? s / 60 + " min" : s / 60 + " min " + s % 60 + " s";
        }
        return s / 3600 + " h" + (s % 3600 != 0 ? " " + (s % 3600) / 60 + " min" : "");
    }

    public static String ev(int thirds) {
        return thirds / 3 + "," + (thirds % 3 == 0 ? "0" : thirds % 3 == 1 ? "3" : "7") + " EV";
    }

    // ---- state ----

    public boolean isRunning() {
        return running != Mode.OFF;
    }

    public Mode running() {
        return running;
    }

    /** One line for the screen and MCP, null when nothing runs */
    public String status() {
        if (running == Mode.OFF) {
            return null;
        }
        long now = SystemClock.uptimeMillis();
        switch (running) {
            case INTERVAL: {
                String next = nextAt > now ? duration((int) ((nextAt - now + 999) / 1000)) : "jetzt";
                return (done == 0 && nextAt > now ? "Intervall startet in " + next : "Intervall " + done + "/" + total + " · nächste in " + next)
                        + (film != null ? " · Film " + film.frames() + " Bilder" : "");
            }
            case TIMELAPSE:
                return tlStatus(now);
            case FOCUS:
                return "Fokusreihe " + done + "/" + total
                    + (session.focusMaxPosition() > 0 ? " · Fokus " + session.focusPosition() + "/" + session.focusMaxPosition() : "");
            case BRACKET:
                return "Belichtungsreihe " + done + "/" + total;
            case BULB: {
                int left = (int) Math.max(0, (bulbEnd - now + 999) / 1000);
                int elapsed = (int) ((now - startedAt) / 1000);
                return "BULB " + clock(elapsed) + " / " + clock(elapsed + left);
            }
            default:
                return null;
        }
    }

    private static String clock(int s) {
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---- start and stop ----

    /** Full press of the shutter while a sequence is selected; returns a message for MCP */
    public String onFullPress() {
        if (isRunning()) {
            stop("abgebrochen");
            return "Ablauf abgebrochen";
        }
        Mode m = mode();
        if (m == Mode.OFF) {
            return null;
        }
        done = 0;
        startedAt = SystemClock.uptimeMillis();
        switch (m) {
            case INTERVAL: return startInterval();
            case TIMELAPSE: return startTimelapse();
            case FOCUS: return startFocusBracket();
            case BRACKET: return startBracket();
            case BULB: return startBulb();
            default: return null;
        }
    }

    public void stop(String why) {
        if (running == Mode.OFF) {
            return;
        }
        Mode was = running;
        handler.removeCallbacksAndMessages(null);
        running = Mode.OFF;
        if (was == Mode.BULB) {
            session.stopBulb();
        }
        if (was == Mode.FOCUS && focusModeBefore != null) {
            Settings.get("afmf").write(session, focusModeBefore);
            focusModeBefore = null;
        }
        if (was == Mode.BRACKET) {
            restoreExposure();
        }
        if (was == Mode.TIMELAPSE) {
            tlLog("ende " + why);
            if (focusModeBefore != null) {
                Settings.get("afmf").write(session, focusModeBefore);
                focusModeBefore = null;
            }
        }
        if (was == Mode.INTERVAL) {
            session.setExposureLock(false);
            if (focusModeBefore != null) {
                Settings.get("afmf").write(session, focusModeBefore);
                focusModeBefore = null;
            }
        }
        String filmInfo = closeFilm();
        Log.info("sequence " + was + " " + why + " after " + done + " shots");
        host.message(was.label + " " + why + " (" + done + " Aufnahmen)" + (filmInfo != null ? ", " + filmInfo : ""), !why.equals("fertig"));
        host.redraw();
    }

    /** The camera reported an exposure */
    public void onShot() {
        if (running == Mode.OFF || !waitingShot) {
            return;
        }
        if (running == Mode.TIMELAPSE && tlBulbOpen && SystemClock.uptimeMillis() < tlBulbEnd) {
            Log.info("timelapse bulb: shutter callback while open");
            return;
        }
        if (running == Mode.BULB && SystemClock.uptimeMillis() < bulbEnd) {
            // Some cameras report the opening of the shutter; the exposure goes on
            Log.info("bulb: shutter callback while open");
            return;
        }
        waitingShot = false;
        done++;
        host.keepAwake();
        host.redraw();
        switch (running) {
            case INTERVAL:
                // Live view frame for the film, a moment after the shot when the preview is back
                if (film != null) {
                    handler.postDelayed(grabFrame, 700);
                }
                if (done >= total) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            stop("fertig");
                        }
                    }, film != null ? 1200 : 0);
                }
                break;
            case TIMELAPSE:
                if (film != null) {
                    handler.postDelayed(grabFrame, 700);
                }
                tlAfterShot();
                if (total > 0 && done >= total) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            stop("fertig");
                        }
                    }, film != null ? 1200 : 0);
                }
                break;
            case FOCUS:
                handler.postDelayed(focusNext, 400 + get("fb.pause", 0) * 1000L);
                break;
            case BRACKET:
                handler.postDelayed(bracketNext, 400);
                break;
            case BULB:
                stop("fertig");
                break;
            default:
                break;
        }
    }

    private boolean shoot() {
        waitingShot = session.captureSingle();
        if (!waitingShot) {
            // Camera still busy (writing): try again shortly
            return false;
        }
        return true;
    }

    // ---- interval ----

    private String startInterval() {
        total = get("iv.count", 100);
        int delay = get("iv.start", 0);
        running = Mode.INTERVAL;
        nextAt = SystemClock.uptimeMillis() + delay * 1000L;
        // Focus once at the start and keep it (MF), unless every picture should focus again
        focusModeBefore = null;
        String af = Settings.get("afmf").safeGet(session.current());
        if (get("iv.af", 0) == 0 && af != null && !"manual".equals(af)) {
            focusModeBefore = af;
            host.startFocus();
            nextAt = Math.max(nextAt, SystemClock.uptimeMillis() + 1500);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (running == Mode.INTERVAL) {
                        host.cancelFocus();
                        Settings.get("afmf").write(session, "manual");
                    }
                }
            }, 1300);
        }
        if (get("iv.ael", 0) == 1) {
            session.setExposureLock(true);
        }
        int fps = get("iv.film", 25);
        film = null;
        if (fps > 0) {
            try {
                filmFile = nextFilmFile();
                film = new MjpegAvi(filmFile, fps);
                film.setInfo(filmInfo());
            } catch (Exception e) {
                Log.error("film", e);
                host.message("Zeitrafferfilm nicht möglich: " + e.getMessage(), true);
            }
        }
        handler.post(intervalTick);
        Log.info("interval start: " + total + " shots every " + get("iv.interval", 5) + " s, delay " + delay + " s, film " + fps);
        host.redraw();
        return "Intervallaufnahme gestartet: " + total + " Bilder alle " + duration(get("iv.interval", 5));
    }

    private final Runnable intervalTick = new Runnable() {
        @Override
        public void run() {
            if (running != Mode.INTERVAL) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now >= nextAt && !waitingShot && done < total) {
                if (shoot()) {
                    nextAt += get("iv.interval", 5) * 1000L;
                    if (nextAt < now) {
                        // The picture took longer than the interval: keep the rhythm from now on
                        nextAt = now + get("iv.interval", 5) * 1000L;
                    }
                }
            }
            host.redraw();
            handler.postDelayed(this, 250);
        }
    };

    // ---- time-lapse (stage 4, modelled on the LRTimelapse Pro Timer) ----

    private TimelapsePlan tlPlan;
    private TimelapsePlan.Grail grail;
    private long tlStartAt;
    private long tlStartWall;
    private String tlPause;
    private boolean tlBulbOpen;
    private long tlBulbEnd;
    private volatile boolean measuring;
    private String lastImage;
    private String tlLastAction = "";
    /** Time-lapse log; opened, appended and closed per line so it can be read during the run */
    private File tlCsv;
    private boolean tlDirKnown;
    private int tlShutterSign = 1;

    public double tlInterval() {
        return get("tl.interval", 50) / 10.0;
    }

    public TimelapsePlan.Grail grail() {
        return grail;
    }

    private String startTimelapse() {
        int hg = get("tl.hg", 0);
        int bulb = get("tl.bulb", 0);
        if (hg != 0 && session.exposureMode() != CameraSession.ExposureMode.M) {
            host.message("Holy Grail braucht den Modus M", true);
            return "Holy Grail braucht den Modus M";
        }
        if (bulb > 0 && !session.isBulb()) {
            host.message("Für Langzeit-Zeitraffer Modus M und Verschlusszeit BULB einstellen", true);
            return "Verschlusszeit steht nicht auf BULB";
        }
        double iv = tlInterval();
        if (bulb > 0 && iv < bulb + 2) {
            iv = bulb + 2;
            host.message("Intervall auf " + iv + " s verlängert (Belichtung " + bulb + " s + Pause)", true);
        }
        total = get("tl.count", 0);
        tlPlan = new TimelapsePlan(iv);
        grail = hg == 0 ? null : new TimelapsePlan.Grail(hg == 1 ? 1 : -1);
        running = Mode.TIMELAPSE;
        lastImage = newestImageName();
        tlDirKnown = false;
        long now = SystemClock.uptimeMillis();
        tlStartAt = now + get("tl.delay", 0) * 1000L;
        if (get("tl.at", 0) == 1) {
            java.util.Calendar c = CameraClock.now();
            java.util.Calendar t = (java.util.Calendar) c.clone();
            t.set(java.util.Calendar.HOUR_OF_DAY, get("tl.ath", 20));
            t.set(java.util.Calendar.MINUTE, get("tl.atm", 0));
            t.set(java.util.Calendar.SECOND, 0);
            t.set(java.util.Calendar.MILLISECOND, 0);
            if (t.before(c)) {
                t.add(java.util.Calendar.DAY_OF_MONTH, 1);
            }
            tlStartAt = now + (t.getTimeInMillis() - c.getTimeInMillis());
        }
        nextAt = tlStartAt;
        tlStartWall = System.currentTimeMillis();
        focusModeBefore = null;
        String af = Settings.get("afmf").safeGet(session.current());
        if (af != null && !"manual".equals(af)) {
            // Focus once at the start and keep it (MF)
            focusModeBefore = af;
            host.startFocus();
            nextAt = Math.max(nextAt, now + 1500);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (running == Mode.TIMELAPSE) {
                        host.cancelFocus();
                        Settings.get("afmf").write(session, "manual");
                    }
                }
            }, 1300);
        }
        int fps = get("tl.film", 0);
        film = null;
        if (fps > 0) {
            try {
                filmFile = nextFilmFile();
                film = new MjpegAvi(filmFile, fps);
                film.setInfo(filmInfo());
            } catch (Exception e) {
                Log.error("film", e);
            }
        }
        tlOpenCsv();
        tlLog("start intervall " + iv + " s, bilder " + (total == 0 ? "unbegrenzt" : total) + ", holy grail "
                + (grail == null ? "aus" : grail.direction > 0 ? "sonnenuntergang" : "sonnenaufgang") + ", bulb " + bulb);
        handler.post(tlTick);
        host.redraw();
        return "Zeitraffer gestartet: alle " + fmtSec(iv) + (total > 0 ? ", " + total + " Bilder" : "");
    }

    private final Runnable tlTick = new Runnable() {
        @Override
        public void run() {
            if (running != Mode.TIMELAPSE) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            host.keepAwake();
            // BULB: close the shutter when the exposure time is over
            if (tlBulbOpen && now >= tlBulbEnd) {
                tlBulbOpen = false;
                session.stopBulb();
            }
            tlPause = null;
            if (now < tlStartAt) {
                tlPause = "Start in " + duration((int) ((tlStartAt - now + 999) / 1000));
            } else if (get("tl.win", 0) == 1) {
                long wait = TimelapsePlan.untilWindow(CameraClock.now(), get("tl.wfrom", 8) * 60,
                        get("tl.wto", 18) * 60, get("tl.wdays", 127));
                if (wait > 0) {
                    tlPause = wait == Long.MAX_VALUE ? "kein Wochentag gewählt" : "Pause bis Zeitfenster, " + duration((int) (wait / 1000));
                    nextAt = now + Math.min(wait, 60000);
                }
            }
            if (tlPause == null && now >= nextAt && !waitingShot && !tlBulbOpen && !measuring && (total == 0 || done < total)) {
                boolean ok;
                int bulb = get("tl.bulb", 0);
                if (bulb > 0) {
                    ok = session.startBulb();
                    if (ok) {
                        waitingShot = true;
                        tlBulbOpen = true;
                        tlBulbEnd = now + bulb * 1000L;
                    }
                } else {
                    ok = shoot();
                }
                if (ok) {
                    nextAt += (long) (tlPlan.interval(now) * 1000);
                    if (nextAt < now) {
                        nextAt = now + (long) (tlPlan.interval(now) * 1000);
                    }
                }
            }
            host.redraw();
            handler.postDelayed(this, 200);
        }
    };

    private String tlStatus(long now) {
        StringBuilder b = new StringBuilder();
        b.append("ZR ").append(done).append(total > 0 ? "/" + total : "");
        if (tlPause != null) {
            return b.append(" · ").append(tlPause).toString();
        }
        double iv = tlPlan.interval(now);
        b.append(" · ").append(fmtSec(iv)).append(tlPlan.ramping() ? "*" : "");
        b.append(" · nächste ").append(nextAt > now ? duration((int) ((nextAt - now + 999) / 1000)) : "jetzt");
        int fps = get("tl.film", 0) > 0 ? get("tl.film", 0) : 25;
        b.append(" · ").append(fps).append("p ").append(TimelapsePlan.filmLength(done, fps));
        if (total > 0) {
            b.append(" · Rest ").append(duration((int) ((total - done) * iv)));
        }
        double sh = session.shutterSeconds();
        int bulb = get("tl.bulb", 0);
        double expo = bulb > 0 ? bulb : sh;
        if (expo > 0 && iv - expo < 1.0) {
            b.append(" · Pause zu kurz!");
        }
        if (grail != null) {
            b.append(" · HG ").append(grail.hasReference() ? String.format(Locale.US, "%+.1f", grail.lastDeviation).replace('.', ',') : "Referenz …");
        }
        return b.toString();
    }

    /** Starts an interval ramp to target seconds within minutes, while the time-lapse runs */
    public String tlRamp(double target, int minutes) {
        if (running != Mode.TIMELAPSE) {
            return "kein Zeitraffer aktiv";
        }
        long now = SystemClock.uptimeMillis();
        tlPlan.startRamp(target, minutes * 60000L, now);
        tlLog("rampe nach " + target + " s in " + minutes + " min");
        return "Intervall-Rampe: " + fmtSec(tlPlan.interval(now)) + " → " + fmtSec(target) + " in " + minutes + " min";
    }

    public String tlRampStop() {
        if (running != Mode.TIMELAPSE) {
            return "kein Zeitraffer aktiv";
        }
        tlPlan.stopRamp(SystemClock.uptimeMillis());
        return "Rampe angehalten bei " + fmtSec(tlPlan.interval(SystemClock.uptimeMillis()));
    }

    /** Moves the Holy Grail reference by thirds of an EV (+ brighter pictures) */
    public String tlReference(int thirds) {
        if (grail == null) {
            return "Holy Grail ist aus";
        }
        grail.offsetThirds += thirds;
        tlLog("referenz " + grail.offsetThirds + "/3 EV");
        return "Referenz " + ev(grail.offsetThirds);
    }

    public static String fmtSec(double s) {
        if (s >= 10 || Math.abs(s - Math.round(s)) < 0.05) {
            return Math.round(s) + " s";
        }
        return String.format(Locale.US, "%.1f s", s).replace('.', ',');
    }

    /** After each picture: measure it (from the card) and let the Holy Grail decide */
    private void tlAfterShot() {
        if (grail == null) {
            tlLog("bild " + done + " " + session.shutterText() + " iso " + Settings.get("iso").safeGet(session.current()));
            return;
        }
        measuring = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                double ev = Double.NaN;
                String name = null;
                try {
                    // Wait until the new picture is on the card (at most a few seconds)
                    for (int i = 0; i < 40; i++) {
                        File f = newestImage();
                        if (f != null && !f.getName().equals(lastImage) && f.length() > 0) {
                            Thread.sleep(400);
                            name = f.getName();
                            ev = measure(f);
                            break;
                        }
                        Thread.sleep(250);
                    }
                } catch (Throwable t) {
                    Log.error("tl measure", t);
                }
                final double fev = ev;
                final String fname = name;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        measuring = false;
                        if (fname != null) {
                            lastImage = fname;
                        }
                        tlGrailStep(fev, fname);
                    }
                });
            }
        }, "tl-measure").start();
    }

    private void tlGrailStep(double ev, String name) {
        if (running != Mode.TIMELAPSE || grail == null) {
            return;
        }
        int step = grail.decide(ev);
        String action = "";
        // Values the picture was taken with, before a lever moves
        final String shotShutter = session.shutterText();
        final String shotIso = Settings.get("iso").safeGet(session.current());
        if (step != 0) {
            double iv = tlPlan.interval(SystemClock.uptimeMillis());
            double maxSh = Math.max(0.1, iv - get("tl.dark", 2));
            CameraSession.Params p = session.current();
            int iso = parseInt(Settings.get("iso").safeGet(p));
            TimelapsePlan.Lever lv = TimelapsePlan.lever(step, session.shutterSeconds(), maxSh, 1.0 / 4000,
                    iso, get("tl.isomin", 100), get("tl.isomax", 3200));
            switch (lv) {
                case SHUTTER_LONGER:
                case SHUTTER_SHORTER:
                    tlShutter(lv == TimelapsePlan.Lever.SHUTTER_LONGER ? 1 : -1);
                    action = lv == TimelapsePlan.Lever.SHUTTER_LONGER ? "zeit länger" : "zeit kürzer";
                    break;
                case ISO_UP:
                case ISO_DOWN:
                    action = tlIso(lv == TimelapsePlan.Lever.ISO_UP ? 1 : -1);
                    break;
                default:
                    action = "grenze erreicht";
                    break;
            }
        }
        tlLastAction = action;
        tlLog(String.format(Locale.US, "bild %d %s ev %.2f abw %+.2f %s iso %s %s", done, name, ev, grail.lastDeviation,
                shotShutter, shotIso, action));
        host.redraw();
    }

    /** One third longer (+1) or shorter (-1); the direction of the camera call is checked once */
    private void tlShutter(final int dir) {
        final double before = session.shutterSeconds();
        session.adjustShutterSpeed(dir * tlShutterSign);
        if (!tlDirKnown) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    double after = session.shutterSeconds();
                    if (after > 0 && before > 0 && after != before) {
                        tlDirKnown = true;
                        if ((after > before) != (dir > 0)) {
                            session.adjustShutterSpeed(-2 * dir * tlShutterSign);
                            tlShutterSign = -tlShutterSign;
                            Log.info("timelapse: shutter direction inverted");
                        }
                    }
                }
            }, 350);
        }
    }

    private String tlIso(int dir) {
        Setting iso = Settings.get("iso");
        CameraSession.Params p = session.current();
        java.util.List<String> vals = iso.safeValues(p);
        int cur = parseInt(iso.safeGet(p));
        String best = null;
        for (String v : vals) {
            int i = parseInt(v);
            if (i < 50 || i > 51200) {
                continue;
            }
            if (dir > 0 && i > cur && (best == null || i < parseInt(best))) {
                best = v;
            }
            if (dir < 0 && i < cur && (best == null || i > parseInt(best))) {
                best = v;
            }
        }
        if (best == null) {
            return "iso grenze";
        }
        iso.write(session, best);
        return (dir > 0 ? "iso höher → " : "iso niedriger → ") + best;
    }

    private static int parseInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Brightness of a picture: ARW from the sensor data, JPEG from a small decoded copy */
    private static double measure(File f) throws Exception {
        String n = f.getName().toUpperCase(Locale.US);
        if (n.endsWith(".ARW")) {
            RawFile r = new RawFile(f);
            try {
                return r.brightnessEv(64);
            } finally {
                r.close();
            }
        }
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = 16;
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) {
            return Double.NaN;
        }
        int[] hist = new int[256];
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int c : row) {
                hist[((c >> 16 & 0xFF) * 77 + (c >> 8 & 0xFF) * 150 + (c & 0xFF) * 29) >> 8]++;
            }
        }
        b.recycle();
        long half = (long) w * h / 2, acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc > half) {
                double v = (i + 0.5) / 255.0;
                double lin = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
                return Math.log(lin) / Math.log(2);
            }
        }
        return Double.NaN;
    }

    /** Newest picture of the newest DCIM folder (ARW preferred), by name */
    private static File newestImage() {
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
        File[] dirs = dcim.listFiles();
        File dir = null;
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && (dir == null || d.getName().compareTo(dir.getName()) > 0)) {
                    dir = d;
                }
            }
        }
        File[] fs = dir != null ? dir.listFiles() : null;
        File best = null;
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName().toUpperCase(Locale.US);
                if (!n.endsWith(".ARW") && !n.endsWith(".JPG")) {
                    continue;
                }
                String stem = n.substring(0, n.length() - 4);
                String bestStem = best == null ? null : best.getName().toUpperCase(Locale.US).substring(0, best.getName().length() - 4);
                int cmp = best == null ? 1 : stem.compareTo(bestStem);
                if (cmp > 0 || (cmp == 0 && n.endsWith(".ARW"))) {
                    best = f;
                }
            }
        }
        return best;
    }

    private static String newestImageName() {
        File f = newestImage();
        return f != null ? f.getName() : "";
    }

    private void tlOpenCsv() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "OMKAMERA");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = null;
            for (int i = 1; i < 10000; i++) {
                f = new File(dir, String.format(Locale.US, "ZR%04d.LOG", i));
                if (!f.exists()) {
                    break;
                }
            }
            new java.io.FileOutputStream(f).close();
            tlCsv = f;
        } catch (Exception e) {
            tlCsv = null;
            Log.error("tl log", e);
        }
    }

    private void tlLog(String line) {
        String l = CameraClock.format("HH:mm:ss") + " " + line;
        Log.info("timelapse " + line);
        if (tlCsv != null) {
            java.io.Writer w = null;
            try {
                w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(tlCsv, true), "UTF-8");
                w.write(l + "\n");
            } catch (Exception e) {
                Log.error("tl log", e);
            } finally {
                if (w != null) {
                    try {
                        w.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (line.startsWith("ende")) {
                tlCsv = null;
            }
        }
    }

    private final Runnable grabFrame = new Runnable() {
        @Override
        public void run() {
            final MjpegAvi f = film;
            if (f == null) {
                return;
            }
            // The live view frame is read on a worker thread; the camera call blocks shortly
            new Thread(new Runnable() {
                @Override
                public void run() {
                    // Right after a picture the camera has no live view for a moment: ask again
                    byte[] jpeg = null;
                    for (int i = 0; i < 12 && jpeg == null && film == f; i++) {
                        jpeg = session.liveFrame();
                        if (jpeg == null) {
                            try {
                                Thread.sleep(250);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                    if (jpeg == null) {
                        Log.info("film: no live view frame");
                    }
                    synchronized (Sequencer.this) {
                        try {
                            if (film == f && jpeg != null) {
                                f.add(jpeg);
                            }
                        } catch (Exception e) {
                            Log.error("film frame", e);
                        }
                    }
                }
            }, "film-frame").start();
        }
    };

    private File nextFilmFile() {
        File dir = new File(Environment.getExternalStorageDirectory(), "OMKAMERA");
        for (int i = 1; i < 10000; i++) {
            File f = new File(dir, String.format(Locale.US, "ZR%04d.AVI", i));
            if (!f.exists()) {
                return f;
            }
        }
        return new File(dir, "ZR9999.AVI");
    }

    private String closeFilm() {
        synchronized (this) {
            if (film == null) {
                return null;
            }
            int n = film.frames();
            try {
                film.close();
            } catch (Exception e) {
                Log.error("film close", e);
            }
            film = null;
            Log.info("film " + filmFile.getPath() + ": " + n + " frames, " + filmFile.length() / 1024 + " KB");
            if (n == 0) {
                filmFile.delete();
                return null;
            }
            return "Film OMKAMERA/" + filmFile.getName() + " mit " + n + " Bildern";
        }
    }

    // ---- focus bracket ----

    private String startFocusBracket() {
        if (!session.focusDriveSupported()) {
            // The a6000 has no focus drive for apps; shiftFocusPosition freezes it completely
            host.message("Fokusreihe: Diese Kamera kann den Fokus für Apps nicht verschieben", true);
            return "Fokusreihe an dieser Kamera nicht möglich (kein Fokusantrieb)";
        }
        total = get("fb.count", 10);
        running = Mode.FOCUS;
        // Focus once, then hold the focus by switching to MF while the steps run
        focusModeBefore = Settings.get("afmf").safeGet(session.current());
        if (!"manual".equals(focusModeBefore)) {
            host.startFocus();
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (running != Mode.FOCUS) {
                    return;
                }
                if (!"manual".equals(focusModeBefore)) {
                    host.cancelFocus();
                    Settings.get("afmf").write(session, "manual");
                } else {
                    focusModeBefore = null;
                }
                focusShot();
            }
        }, "manual".equals(focusModeBefore) ? 0 : 1200);
        host.redraw();
        return "Fokusreihe gestartet: " + total + " Bilder, Schrittweite " + get("fb.step", 3);
    }

    private void focusShot() {
        if (running != Mode.FOCUS) {
            return;
        }
        if (!shoot()) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    focusShot();
                }
            }, 300);
        }
    }

    private final Runnable focusNext = new Runnable() {
        @Override
        public void run() {
            if (running != Mode.FOCUS) {
                return;
            }
            if (done >= total) {
                stop("fertig");
                return;
            }
            session.driveFocus(get("fb.step", 3), true, new CameraSession.ShiftResult() {
                @Override
                public void done(Boolean ok) {
                    if (running != Mode.FOCUS) {
                        return;
                    }
                    if (ok == null) {
                        stop("abgebrochen (Kamera verschiebt den Fokus nicht)");
                        return;
                    }
                    if (!ok) {
                        // End of the range (infinity) reached, like on the camera
                        stop("fertig (Unendlich erreicht)");
                        return;
                    }
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            focusShot();
                        }
                    }, 250);
                }
            });
        }
    };

    // ---- extended exposure bracket ----

    private String startBracket() {
        int n = get("eb.count", 5);
        int step = get("eb.step", 3);
        boolean minusFirst = get("eb.order", 0) == 1;
        // Offsets in thirds of an EV, in the chosen order
        offsets = new int[n];
        int half = n / 2;
        if (minusFirst) {
            for (int i = 0; i < n; i++) {
                offsets[i] = (i - half) * step;
            }
        } else {
            offsets[0] = 0;
            int k = 1;
            for (int d = 1; d <= half; d++) {
                offsets[k++] = -d * step;
                offsets[k++] = d * step;
            }
        }
        total = n;
        manual = session.exposureMode() == CameraSession.ExposureMode.M;
        Setting ev = Settings.get("ev");
        evBase = 0;
        try {
            evBase = Integer.parseInt(ev.safeGet(session.current()));
        } catch (Exception ignored) {
        }
        shutterApplied = 0;
        shutterSign = 1;
        directionKnown = false;
        running = Mode.BRACKET;
        Log.info("bracket+ " + n + " x " + step + "/3 EV, manual=" + manual);
        handler.post(bracketNext);
        return "Belichtungsreihe+ gestartet: " + n + " Bilder, Stufe " + ev(step);
    }

    private final Runnable bracketNext = new Runnable() {
        @Override
        public void run() {
            if (running != Mode.BRACKET) {
                return;
            }
            if (done >= total) {
                stop("fertig");
                return;
            }
            setOffset(offsets[done]);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (running != Mode.BRACKET) {
                        return;
                    }
                    if (checkShutterDirection()) {
                        // Direction was wrong and has been corrected: wait for the camera again
                        handler.postDelayed(this, 350);
                        return;
                    }
                    if (!shoot()) {
                        handler.postDelayed(this, 300);
                    }
                }
            }, 350);
        }
    };

    /** Brighter by thirds of an EV: exposure compensation in P/A/S, shutter speed in M */
    private void setOffset(int thirds) {
        if (!manual) {
            int target = Math.max(-15, Math.min(15, evBase + thirds));
            if (target != evBase + thirds) {
                host.message("Belichtungskorrektur am Ende des Bereichs (±5 EV)", true);
            }
            Settings.get("ev").write(session, String.valueOf(target));
            return;
        }
        int delta = thirds - shutterApplied;
        if (delta == 0) {
            return;
        }
        checkBefore = directionKnown ? 0 : session.shutterSeconds();
        checkDelta = delta;
        session.adjustShutterSpeed(delta * shutterSign);
        shutterApplied = thirds;
    }

    private boolean directionKnown;
    private double checkBefore;
    private int checkDelta;

    /**
     * The direction of adjustShutterSpeed is not documented: after the first change, compare the
     * new speed with the old one and turn back if the picture would get darker instead of brighter.
     * Returns true if a correction was made.
     */
    private boolean checkShutterDirection() {
        if (!manual || directionKnown || checkDelta == 0 || checkBefore <= 0) {
            return false;
        }
        double after = session.shutterSeconds();
        if (after <= 0 || after == checkBefore) {
            return false;
        }
        directionKnown = true;
        if ((after > checkBefore) != (checkDelta > 0)) {
            session.adjustShutterSpeed(-2 * checkDelta * shutterSign);
            shutterSign = -shutterSign;
            Log.info("bracket+: shutter direction inverted");
            return true;
        }
        return false;
    }

    private void restoreExposure() {
        if (offsets == null) {
            return;
        }
        if (!manual) {
            Settings.get("ev").write(session, String.valueOf(evBase));
        } else if (shutterApplied != 0) {
            session.adjustShutterSpeed(-shutterApplied * shutterSign);
            shutterApplied = 0;
        }
    }

    // ---- BULB timer ----

    private String startBulb() {
        if (!session.isBulb()) {
            host.message("Für den BULB-Timer Modus M und Verschlusszeit BULB einstellen", true);
            return "Verschlusszeit steht nicht auf BULB";
        }
        int secs = get("bt.time", 60);
        if (!session.startBulb()) {
            host.message("BULB konnte nicht gestartet werden", true);
            return "BULB nicht gestartet";
        }
        running = Mode.BULB;
        total = 1;
        waitingShot = true;
        bulbEnd = SystemClock.uptimeMillis() + secs * 1000L;
        handler.post(bulbTick);
        return "BULB läuft " + duration(secs);
    }

    private final Runnable bulbTick = new Runnable() {
        @Override
        public void run() {
            if (running != Mode.BULB) {
                return;
            }
            host.keepAwake();
            host.redraw();
            if (SystemClock.uptimeMillis() >= bulbEnd) {
                session.stopBulb();
                // The shot callback ends the sequence; if it does not come, end it after a while
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (running == Mode.BULB) {
                            stop("fertig");
                        }
                    }
                }, 3000);
                return;
            }
            handler.postDelayed(this, 500);
        }
    };
}
