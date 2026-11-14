package com.github.ronyx2.omkamera;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * ISO automatic with a minimum shutter speed, like "ISO AUTO Min. SS" of newer Sony bodies,
 * which the ILCE-6000 lacks. In P and A the app watches the shutter speed the camera chooses
 * and sets the ISO so that it stays at or below the limit for the current focal length:
 * 1 / (crop × focal length), shifted by whole stops (slower … faster), or a fixed time.
 * The ISO stays between a minimum and a maximum. Regulates after the fact: a sudden change
 * of light needs a moment.
 */
public class AutoIso {

    /** Menu choices: label and stops relative to 1/(crop × f), or a fixed time in 1/x s */
    public static final String[] LABELS = {"Aus", "Langsamer", "Langsam", "Standard", "Schnell", "Schneller",
        "1/30 s", "1/60 s", "1/125 s", "1/250 s", "1/500 s"};
    private static final int[] STOPS = {0, -2, -1, 0, 1, 2};
    private static final int[] FIXED = {0, 0, 0, 0, 0, 0, 30, 60, 125, 250, 500};
    public static final int[] ISO_STEPS = {100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600};
    static final double CROP = 1.5;

    private final CameraSession session;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler();
    private boolean running;
    private long lastChange;
    private String state = "aus";
    /** ISO setting before the automatic took over, restored when it is switched off */
    private String isoBefore;

    public interface Gate {
        /** False while a menu is open, a picture is taken or the exposure is locked */
        boolean allowed();
    }

    private final Gate gate;

    public AutoIso(CameraSession session, SharedPreferences prefs, Gate gate) {
        this.session = session;
        this.prefs = prefs;
        this.gate = gate;
    }

    public int mode() {
        int m = prefs.getInt("aiso.mode", 0);
        return m < 0 || m >= LABELS.length ? 0 : m;
    }

    public void setMode(int m) {
        prefs.edit().putInt("aiso.mode", m).apply();
        apply();
    }

    public int isoMin() {
        return prefs.getInt("aiso.min", 100);
    }

    public int isoMax() {
        return prefs.getInt("aiso.max", 6400);
    }

    public void setRange(int min, int max) {
        prefs.edit().putInt("aiso.min", min).putInt("aiso.max", Math.max(min, max)).apply();
    }

    /** Lens with optical stabiliser (OSS): one stop longer allowed, if switched on */
    public boolean ossBonus() {
        return prefs.getBoolean("aiso.oss", false);
    }

    public void setOssBonus(boolean on) {
        prefs.edit().putBoolean("aiso.oss", on).apply();
    }

    public String state() {
        return state;
    }

    /** Limit in seconds for the current focal length, 0 if off */
    public double limit() {
        int m = mode();
        if (m == 0) {
            return 0;
        }
        if (FIXED[m] > 0) {
            return 1.0 / FIXED[m];
        }
        float f = session.focalLength();
        double base = f > 0 ? 1.0 / (CROP * f) : 1.0 / 60;
        double l = base / Math.pow(2, STOPS[m]);
        if (ossBonus()) {
            String lens = session.lensText();
            if (lens != null && lens.toUpperCase(java.util.Locale.US).contains("OSS")) {
                l *= 2;
            }
        }
        return l;
    }

    /** "1/112 s" */
    public String limitText() {
        double l = limit();
        if (l <= 0) {
            return "–";
        }
        return l >= 1 ? Math.round(l) + " s" : "1/" + Math.round(1 / l) + " s";
    }

    /** Starts or stops the regulation after a change of the settings */
    public void apply() {
        boolean want = mode() != 0;
        if (want && !running) {
            running = true;
            isoBefore = Settings.get("iso").safeGet(session.current());
            Log.info("auto iso on, iso before " + isoBefore);
            handler.post(tick);
        } else if (!want && running) {
            running = false;
            handler.removeCallbacks(tick);
            if (isoBefore != null) {
                Settings.get("iso").write(session, isoBefore);
            }
            state = "aus";
            Log.info("auto iso off, iso back to " + isoBefore);
        }
    }

    public void stop() {
        handler.removeCallbacks(tick);
        running = false;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                step();
            } catch (Throwable t) {
                Log.error("auto iso", t);
            }
            handler.postDelayed(this, 400);
        }
    };

    private void step() {
        CameraSession.ExposureMode em = session.exposureMode();
        if (em != CameraSession.ExposureMode.P && em != CameraSession.ExposureMode.A) {
            state = "nur in P und A";
            return;
        }
        if (!gate.allowed() || session.isCapturing() || session.isBulb()) {
            return;
        }
        // Give the camera time to meter again after the last change
        if (SystemClock.uptimeMillis() - lastChange < 900) {
            return;
        }
        double s = session.shutterSeconds();
        double lim = limit();
        if (s <= 0 || lim <= 0) {
            return;
        }
        CameraSession.Params p = session.current();
        Setting iso = Settings.get("iso");
        int cur = parse(iso.safeGet(p));
        List<Integer> steps = isoValues(p);
        if (steps.isEmpty()) {
            state = "ISO-Werte unbekannt";
            return;
        }
        if (cur <= 0) {
            // Camera AUTO: start from the camera's choice is unknown, begin at the minimum
            cur = nearest(steps, isoMin());
            write(iso, cur, "Start");
            return;
        }
        int target = cur;
        if (s > lim * 1.12) {
            // Too slow: raise the ISO by the missing stops
            target = (int) Math.round(cur * s / lim);
        } else if (s < lim * 0.45) {
            // Much faster than needed: lower the ISO, but keep a little reserve
            target = (int) Math.round(cur * s / (lim * 0.8));
        }
        target = Math.max(isoMin(), Math.min(isoMax(), target));
        int q = nearest(steps, target);
        if (q != cur) {
            write(iso, q, String.format(java.util.Locale.US, "Zeit %.4f s, Grenze %.4f s", s, lim));
        } else {
            state = "ISO " + cur + (s > lim * 1.12 ? " (Maximum erreicht)" : "") + ", Grenze " + limitText();
        }
    }

    private void write(Setting iso, int value, String why) {
        CameraSession.Result r = iso.write(session, String.valueOf(value));
        lastChange = SystemClock.uptimeMillis();
        state = "ISO " + value + ", Grenze " + limitText();
        Log.info("auto iso -> " + value + " (" + why + ")" + (r.ok ? "" : " refused"));
    }

    private static int parse(String v) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Numeric ISO values of the camera (without AUTO and multi frame NR codes) */
    private static List<Integer> isoValues(CameraSession.Params p) {
        List<Integer> out = new ArrayList<Integer>();
        for (String v : Settings.get("iso").safeValues(p)) {
            int i = parse(v);
            if (i >= 50 && i <= 51200) {
                out.add(i);
            }
        }
        return out;
    }

    private static int nearest(List<Integer> steps, int target) {
        int best = steps.get(0);
        double bd = Double.MAX_VALUE;
        for (int v : steps) {
            double d = Math.abs(Math.log(v) - Math.log(target));
            if (d < bd) {
                bd = d;
                best = v;
            }
        }
        return best;
    }
}
