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
        OFF("Aus"), INTERVAL("Intervallaufnahme"), FOCUS("Fokusreihe"), BRACKET("Belichtungsreihe+"), BULB("BULB-Timer");
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
        String date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date());
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
