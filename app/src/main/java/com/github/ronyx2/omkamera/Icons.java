package com.github.ronyx2.omkamera;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Simple line icons for settings, drawn with the canvas (no image files).
 * Every icon fits into a square of size {@code s} around ({@code cx}, {@code cy}).
 */
public final class Icons {
    private static final Paint LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint TEXT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF R = new RectF();
    private static final Path PATH = new Path();

    static {
        LINE.setStyle(Paint.Style.STROKE);
        LINE.setStrokeCap(Paint.Cap.ROUND);
        LINE.setStrokeJoin(Paint.Join.ROUND);
        FILL.setStyle(Paint.Style.FILL);
        TEXT.setTextAlign(Paint.Align.CENTER);
        TEXT.setFakeBoldText(true);
    }

    private Icons() {
    }

    /** Returns false if there is no icon for this setting and value; the caller then shows text. */
    public static boolean draw(Canvas c, String id, String value, float cx, float cy, float s, int color) {
        if (value == null) {
            return false;
        }
        LINE.setColor(color);
        LINE.setStrokeWidth(Math.max(1.5f, s / 14));
        FILL.setColor(color);
        TEXT.setColor(color);
        String v = value.toLowerCase();
        if ("drive".equals(id)) {
            return drive(c, v, cx, cy, s);
        } else if ("afarea".equals(id)) {
            return afArea(c, v, cx, cy, s);
        } else if ("wb".equals(id)) {
            return whiteBalance(c, v, cx, cy, s);
        } else if ("metering".equals(id)) {
            return metering(c, v, cx, cy, s);
        } else if ("flash".equals(id)) {
            return flash(c, v, cx, cy, s);
        } else if ("afmf".equals(id)) {
            corners(c, cx, cy, s * 0.8f, s * 0.6f, s * 0.18f);
            text(c, "manual".equals(v) ? "MF" : "AF", cx, cy, s * 0.36f);
            return true;
        }
        // Quality and other text-like values are shown as text by the caller, which reads better
        return false;
    }

    // ---- single icons ----

    private static boolean drive(Canvas c, String v, float cx, float cy, float s) {
        float w = s * 0.5f;
        float h = s * 0.36f;
        if (v.startsWith("timer") || v.contains("self")) {
            float r = s * 0.3f;
            float x = cx - s * 0.1f;
            c.drawCircle(x, cy, r, LINE);
            c.drawLine(x, cy, x, cy - r * 0.65f, LINE);
            c.drawLine(x, cy, x + r * 0.45f, cy + r * 0.2f, LINE);
            c.drawLine(x - r * 0.3f, cy - r - s * 0.08f, x + r * 0.3f, cy - r - s * 0.08f, LINE);
            if (v.startsWith("timer-")) {
                text(c, v.substring(6), cx + s * 0.3f, cy + s * 0.2f, s * 0.26f);
            }
            return true;
        }
        if (v.startsWith("burst")) {
            float x = cx - s * 0.08f;
            for (int i = 2; i >= 0; i--) {
                float o = (i - 1) * s * 0.1f;
                rect(c, x - w / 2 + o, cy - h / 2 - o, x + w / 2 + o, cy + h / 2 - o);
            }
            String sp = v.endsWith("high") ? "H" : v.endsWith("middle") ? "M" : v.endsWith("low") ? "L" : null;
            if (sp != null) {
                text(c, sp, cx + s * 0.34f, cy + s * 0.22f, s * 0.26f);
            }
            return true;
        }
        if (v.startsWith("bracket")) {
            // Three frames of falling brightness, the kind as a small label
            for (int i = 2; i >= 0; i--) {
                float o = (i - 1) * s * 0.1f;
                rect(c, cx - w / 2 + o, cy - h / 2 - o, cx + w / 2 + o, cy + h / 2 - o);
            }
            String k = v.equals("bracket-wb") ? "WB" : v.equals("bracket-dro") ? "DRO" : v.equals("bracket-c") ? "C" : "S";
            text(c, k, cx, cy + s * 0.04f, s * (k.length() > 2 ? 0.2f : 0.24f));
            return true;
        }
        rect(c, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        return true;
    }

    private static boolean afArea(Canvas c, String v, float cx, float cy, float s) {
        if (v.equals("wide") || v.contains("multi")) {
            corners(c, cx, cy, s * 0.86f, s * 0.62f, s * 0.16f);
            return true;
        }
        if (v.contains("zone") || v.contains("semi")) {
            corners(c, cx, cy, s * 0.86f, s * 0.62f, s * 0.12f);
            rect(c, cx - s * 0.18f, cy - s * 0.14f, cx + s * 0.18f, cy + s * 0.14f);
            return true;
        }
        if (v.contains("center")) {
            corners(c, cx, cy, s * 0.86f, s * 0.62f, s * 0.12f);
            rect(c, cx - s * 0.1f, cy - s * 0.08f, cx + s * 0.1f, cy + s * 0.08f);
            return true;
        }
        if (v.contains("flex") || v.contains("local") || v.contains("spot")) {
            corners(c, cx, cy, s * 0.86f, s * 0.62f, s * 0.12f);
            float x = cx + s * 0.16f;
            float y = cy - s * 0.08f;
            rect(c, x - s * 0.09f, y - s * 0.07f, x + s * 0.09f, y + s * 0.07f);
            c.drawLine(x - s * 0.2f, y, x - s * 0.12f, y, LINE);
            return true;
        }
        if (v.contains("track")) {
            corners(c, cx, cy, s * 0.62f, s * 0.62f, s * 0.14f);
            c.drawCircle(cx, cy, s * 0.06f, FILL);
            return true;
        }
        return false;
    }

    private static boolean whiteBalance(Canvas c, String v, float cx, float cy, float s) {
        if (v.equals("auto")) {
            return false;
        }
        if (v.equals("daylight")) {
            sun(c, cx, cy, s);
            return true;
        }
        if (v.contains("cloud")) {
            cloud(c, cx, cy, s);
            return true;
        }
        if (v.equals("shade")) {
            // House with shadow line
            PATH.reset();
            PATH.moveTo(cx - s * 0.32f, cy - s * 0.02f);
            PATH.lineTo(cx, cy - s * 0.3f);
            PATH.lineTo(cx + s * 0.32f, cy - s * 0.02f);
            c.drawPath(PATH, LINE);
            rect(c, cx - s * 0.22f, cy - s * 0.02f, cx + s * 0.22f, cy + s * 0.28f);
            c.drawLine(cx - s * 0.22f, cy + s * 0.28f, cx + s * 0.22f, cy - s * 0.02f, LINE);
            return true;
        }
        if (v.equals("incandescent")) {
            c.drawCircle(cx, cy - s * 0.08f, s * 0.2f, LINE);
            rect(c, cx - s * 0.09f, cy + s * 0.14f, cx + s * 0.09f, cy + s * 0.28f);
            return true;
        }
        if (v.contains("fluorescent")) {
            R.set(cx - s * 0.34f, cy - s * 0.08f, cx + s * 0.34f, cy + s * 0.08f);
            c.drawRoundRect(R, s * 0.08f, s * 0.08f, LINE);
            for (int i = -1; i <= 1; i++) {
                c.drawLine(cx + i * s * 0.2f, cy - s * 0.16f, cx + i * s * 0.2f, cy - s * 0.28f, LINE);
            }
            return true;
        }
        if (v.equals("flash")) {
            bolt(c, cx, cy, s);
            return true;
        }
        if (v.contains("temp") || v.startsWith("custom")) {
            return false;
        }
        return false;
    }

    private static boolean metering(Canvas c, String v, float cx, float cy, float s) {
        rect(c, cx - s * 0.38f, cy - s * 0.28f, cx + s * 0.38f, cy + s * 0.28f);
        if (v.equals("multi")) {
            c.drawCircle(cx, cy, s * 0.16f, LINE);
            c.drawCircle(cx, cy, s * 0.04f, FILL);
            return true;
        }
        if (v.contains("center")) {
            c.drawCircle(cx, cy, s * 0.18f, LINE);
            return true;
        }
        if (v.contains("spot")) {
            c.drawCircle(cx, cy, s * 0.09f, FILL);
            return true;
        }
        return true;
    }

    private static boolean flash(Canvas c, String v, float cx, float cy, float s) {
        bolt(c, cx - (v.equals("auto") ? s * 0.08f : 0), cy, s);
        if (v.equals("off")) {
            c.drawLine(cx - s * 0.32f, cy + s * 0.32f, cx + s * 0.32f, cy - s * 0.32f, LINE);
        } else if (v.equals("auto")) {
            text(c, "A", cx + s * 0.26f, cy + s * 0.18f, s * 0.28f);
        } else if (v.equals("slow")) {
            text(c, "SL", cx + s * 0.26f, cy + s * 0.18f, s * 0.24f);
        } else if (v.startsWith("rear")) {
            text(c, "R", cx + s * 0.26f, cy + s * 0.18f, s * 0.28f);
        } else if (v.contains("wireless")) {
            text(c, "W", cx + s * 0.26f, cy + s * 0.18f, s * 0.28f);
        }
        return true;
    }

    // ---- building blocks ----

    private static void rect(Canvas c, float l, float t, float r, float b) {
        R.set(l, t, r, b);
        c.drawRect(R, LINE);
    }

    /** Four corner brackets of a frame */
    static void corners(Canvas c, float cx, float cy, float w, float h, float len) {
        float l = cx - w / 2, r = cx + w / 2, t = cy - h / 2, b = cy + h / 2;
        c.drawLine(l, t, l + len, t, LINE);
        c.drawLine(l, t, l, t + len, LINE);
        c.drawLine(r, t, r - len, t, LINE);
        c.drawLine(r, t, r, t + len, LINE);
        c.drawLine(l, b, l + len, b, LINE);
        c.drawLine(l, b, l, b - len, LINE);
        c.drawLine(r, b, r - len, b, LINE);
        c.drawLine(r, b, r, b - len, LINE);
    }

    private static void text(Canvas c, String t, float cx, float cy, float size) {
        TEXT.setTextSize(size);
        c.drawText(t, cx, cy + size * 0.36f, TEXT);
    }

    private static void badge(Canvas c, String t, float cx, float cy, float s) {
        R.set(cx - s * 0.45f, cy - s * 0.22f, cx + s * 0.45f, cy + s * 0.22f);
        c.drawRoundRect(R, s * 0.06f, s * 0.06f, LINE);
        text(c, t, cx, cy, s * 0.26f);
    }

    private static void sun(Canvas c, float cx, float cy, float s) {
        float r = s * 0.15f;
        c.drawCircle(cx, cy, r, LINE);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4;
            float x1 = (float) (cx + Math.cos(a) * r * 1.6f);
            float y1 = (float) (cy + Math.sin(a) * r * 1.6f);
            float x2 = (float) (cx + Math.cos(a) * r * 2.3f);
            float y2 = (float) (cy + Math.sin(a) * r * 2.3f);
            c.drawLine(x1, y1, x2, y2, LINE);
        }
    }

    private static void cloud(Canvas c, float cx, float cy, float s) {
        PATH.reset();
        R.set(cx - s * 0.36f, cy - s * 0.06f, cx - s * 0.08f, cy + s * 0.22f);
        PATH.addArc(R, 90, 180);
        R.set(cx - s * 0.2f, cy - s * 0.26f, cx + s * 0.16f, cy + s * 0.1f);
        PATH.addArc(R, 160, 200);
        R.set(cx + s * 0.04f, cy - s * 0.1f, cx + s * 0.36f, cy + s * 0.22f);
        PATH.addArc(R, 270, 180);
        c.drawPath(PATH, LINE);
        c.drawLine(cx - s * 0.22f, cy + s * 0.22f, cx + s * 0.2f, cy + s * 0.22f, LINE);
    }

    private static void bolt(Canvas c, float cx, float cy, float s) {
        PATH.reset();
        PATH.moveTo(cx + s * 0.06f, cy - s * 0.34f);
        PATH.lineTo(cx - s * 0.16f, cy + s * 0.04f);
        PATH.lineTo(cx + s * 0.02f, cy + s * 0.04f);
        PATH.lineTo(cx - s * 0.06f, cy + s * 0.34f);
        PATH.lineTo(cx + s * 0.18f, cy - s * 0.06f);
        PATH.lineTo(cx, cy - s * 0.06f);
        PATH.close();
        c.drawPath(PATH, FILL);
    }
}
