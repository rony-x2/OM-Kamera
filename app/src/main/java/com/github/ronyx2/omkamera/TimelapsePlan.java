package com.github.ronyx2.omkamera;

import java.util.Calendar;

/**
 * Planning logic of the time-lapse (stage 4, modelled on the LRTimelapse Pro Timer and the
 * "Holy Grail" exposure ramping), without Android classes so it can be tested on a PC:
 * - interval ramping: the interval moves linearly to a target within a given time
 * - time window: shooting only between two clock times on chosen weekdays
 * - Holy Grail: from the brightness of each picture the exposure is moved in 1/3 EV steps
 *   in one direction (sunset: more light, sunrise: less), smoothed, at most one step per
 *   picture, so that LRTimelapse's Holy Grail Wizard can level the steps afterwards.
 */
public final class TimelapsePlan {

    // ---- interval ramping ----

    private double interval;
    private double rampFrom, rampTo;
    private long rampStart, rampMs;
    private boolean ramping;

    public TimelapsePlan(double intervalSeconds) {
        interval = intervalSeconds;
    }

    /** Current interval in seconds; while a ramp runs it moves linearly to the target */
    public double interval(long nowMs) {
        if (ramping) {
            double f = rampMs <= 0 ? 1 : (double) (nowMs - rampStart) / rampMs;
            if (f >= 1) {
                interval = rampTo;
                ramping = false;
            } else {
                interval = rampFrom + (rampTo - rampFrom) * Math.max(0, f);
            }
        }
        return interval;
    }

    public void startRamp(double to, long durationMs, long nowMs) {
        rampFrom = interval(nowMs);
        rampTo = to;
        rampStart = nowMs;
        rampMs = durationMs;
        ramping = true;
    }

    public void stopRamp(long nowMs) {
        interval(nowMs);
        ramping = false;
    }

    public boolean ramping() {
        return ramping;
    }

    public double rampTarget() {
        return rampTo;
    }

    // ---- time window ----

    /**
     * True if the time lies in the window [startMin, endMin) (minutes after midnight, may wrap
     * over midnight) on a day whose bit is set in weekdays (bit 0 = Monday … bit 6 = Sunday).
     */
    public static boolean inWindow(Calendar c, int startMin, int endMin, int weekdays) {
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // Monday = 0
        if (startMin == endMin) {
            return (weekdays & (1 << dow)) != 0;
        }
        if (startMin < endMin) {
            return m >= startMin && m < endMin && (weekdays & (1 << dow)) != 0;
        }
        // Over midnight: the evening part belongs to today, the morning part to yesterday's window
        if (m >= startMin) {
            return (weekdays & (1 << dow)) != 0;
        }
        int yesterday = (dow + 6) % 7;
        return m < endMin && (weekdays & (1 << yesterday)) != 0;
    }

    /** Milliseconds until the next moment inside the window (0 if inside), searched minute by minute */
    public static long untilWindow(Calendar now, int startMin, int endMin, int weekdays) {
        if (weekdays == 0) {
            return Long.MAX_VALUE;
        }
        Calendar c = (Calendar) now.clone();
        if (inWindow(c, startMin, endMin, weekdays)) {
            return 0;
        }
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8 * 24 * 60; i++) {
            c.add(Calendar.MINUTE, 1);
            if (inWindow(c, startMin, endMin, weekdays)) {
                return c.getTimeInMillis() - now.getTimeInMillis();
            }
        }
        return Long.MAX_VALUE;
    }

    // ---- Holy Grail ----

    public static final class Grail {
        /** +1 sunset (pictures get darker, add light), -1 sunrise (remove light) */
        public final int direction;
        /** Correction of the reference in thirds of an EV (the "−"/"+" of LRTimelapse) */
        public int offsetThirds;
        private double ref = Double.NaN;
        private double refSum;
        private int refCount;
        private double smooth;
        private boolean hasSmooth;
        private int cooldown;
        public double lastDeviation;

        public Grail(int direction) {
            this.direction = direction >= 0 ? 1 : -1;
        }

        public boolean hasReference() {
            return !Double.isNaN(ref);
        }

        public double reference() {
            return ref;
        }

        /**
         * Brightness of the last picture in EV (log2 of the median relative to white). Returns
         * +1 for one third more exposure, -1 for one third less, 0 for no change.
         */
        public int decide(double ev) {
            if (Double.isNaN(ev) || Double.isInfinite(ev)) {
                return 0;
            }
            if (Double.isNaN(ref)) {
                // The reference is the mean of the first three pictures
                refSum += ev;
                if (++refCount >= 3) {
                    ref = refSum / refCount;
                }
                return 0;
            }
            double d = ev - (ref + offsetThirds / 3.0);
            smooth = hasSmooth ? 0.5 * smooth + 0.5 * d : d;
            hasSmooth = true;
            lastDeviation = smooth;
            if (cooldown > 0) {
                cooldown--;
                return 0;
            }
            int step = 0;
            if (direction > 0) {
                if (smooth < -0.25) {
                    step = 1;
                } else if (smooth > 0.7) {
                    step = -1;           // clearly too bright: correct back, rarely
                }
            } else {
                if (smooth > 0.25) {
                    step = -1;
                } else if (smooth < -0.7) {
                    step = 1;
                }
            }
            if (step != 0) {
                // The next picture shows the change; count it in and wait one picture
                smooth += step / 3.0;
                cooldown = 1;
            }
            return step;
        }
    }

    // ---- exposure order ----

    /** What to change for one third of an EV, given the limits */
    public enum Lever { SHUTTER_LONGER, SHUTTER_SHORTER, ISO_UP, ISO_DOWN, NONE }

    /**
     * Sunset (more light): longer shutter up to its limit, then higher ISO.
     * Sunrise (less light): lower ISO first (cleaner pictures), then shorter shutter.
     */
    public static Lever lever(int step, double shutter, double maxShutter, double minShutter, int iso, int isoMin, int isoMax) {
        double third = Math.pow(2, 1 / 3.0);
        if (step > 0) {
            if (shutter > 0 && shutter * third <= maxShutter * 1.01) {
                return Lever.SHUTTER_LONGER;
            }
            if (iso < isoMax) {
                return Lever.ISO_UP;
            }
        } else if (step < 0) {
            if (iso > isoMin) {
                return Lever.ISO_DOWN;
            }
            if (shutter > 0 && shutter / third >= minShutter * 0.99) {
                return Lever.SHUTTER_SHORTER;
            }
        }
        return Lever.NONE;
    }

    /** Length of a film from a number of pictures */
    public static String filmLength(int frames, int fps) {
        int s = fps > 0 ? frames / fps : 0;
        return String.format(java.util.Locale.US, "%d'%02d\"", s / 60, s % 60);
    }
}
