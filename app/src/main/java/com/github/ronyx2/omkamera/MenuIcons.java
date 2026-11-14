package com.github.ronyx2.omkamera;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.Locale;

/**
 * Small line icons for the menu: one per tab and per group heading, so a section is found
 * at a glance. Drawn with simple strokes in the colour of the tab, like the setting icons.
 */
final class MenuIcons {
    private static final Paint LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        LINE.setStyle(Paint.Style.STROKE);
        LINE.setStrokeCap(Paint.Cap.ROUND);
        LINE.setStrokeJoin(Paint.Join.ROUND);
        FILL.setStyle(Paint.Style.FILL);
    }

    private MenuIcons() {
    }

    /** Icon for a tab of the main menu */
    static String forTab(String tab) {
        String t = tab.toLowerCase(Locale.GERMANY);
        if (t.startsWith("haupt")) return "home";
        if (t.startsWith("aufnahme")) return "camera";
        if (t.startsWith("belichtung")) return "sun";
        if (t.startsWith("fokus")) return "target";
        if (t.startsWith("netz")) return "wifi";
        if (t.startsWith("einstellung")) return "gear";
        if (t.startsWith("mein")) return "star";
        return null;
    }

    /** Icon for a group heading, found by words in it */
    static String forHeading(String heading) {
        String h = heading.toLowerCase(Locale.GERMANY);
        if (h.contains("qualität") || h.contains("speicher")) return "card";
        if (h.contains("bildfolge")) return "stack";
        if (h.contains("ablauf") || h.contains("zeitraffer")) return "timer";
        if (h.contains("verarbeitung")) return "layers";
        if (h.contains("iso")) return "iso";
        if (h.contains("weißabgleich") || h.contains("farbe")) return "palette";
        if (h.contains("messung")) return "meter";
        if (h.contains("belichtung")) return "sun";
        if (h.contains("blitz")) return "bolt";
        if (h.contains("fokus") || h.contains("af/mf") || h.contains("motiv")) return "target";
        if (h.contains("wlan") || h.contains("fernzugriff")) return "wifi";
        if (h.contains("uhr")) return "clock";
        if (h.contains("anzeige") || h.contains("hilfen")) return "eye";
        if (h.contains("bedienung")) return "gear";
        if (h.contains("info")) return "info";
        if (h.contains("aufnahme")) return "camera";
        return null;
    }

    /** Draws the icon centred at cx/cy with size s; false if there is none */
    static boolean draw(Canvas c, String name, float cx, float cy, float s, int color) {
        if (name == null) {
            return false;
        }
        LINE.setColor(color);
        LINE.setStrokeWidth(Math.max(1.4f, s / 12));
        FILL.setColor(color);
        float h = s / 2;
        RectF r = new RectF();
        Path p = new Path();
        if ("home".equals(name)) {
            p.moveTo(cx - h * 0.8f, cy - h * 0.05f);
            p.lineTo(cx, cy - h * 0.8f);
            p.lineTo(cx + h * 0.8f, cy - h * 0.05f);
            c.drawPath(p, LINE);
            c.drawRect(cx - h * 0.55f, cy - h * 0.2f, cx + h * 0.55f, cy + h * 0.75f, LINE);
        } else if ("camera".equals(name)) {
            r.set(cx - h * 0.85f, cy - h * 0.45f, cx + h * 0.85f, cy + h * 0.7f);
            c.drawRoundRect(r, h * 0.15f, h * 0.15f, LINE);
            c.drawRect(cx - h * 0.35f, cy - h * 0.7f, cx + h * 0.15f, cy - h * 0.45f, LINE);
            c.drawCircle(cx, cy + h * 0.12f, h * 0.32f, LINE);
        } else if ("sun".equals(name)) {
            c.drawCircle(cx, cy, h * 0.35f, LINE);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
                c.drawLine(cx + dx * h * 0.6f, cy + dy * h * 0.6f, cx + dx * h * 0.9f, cy + dy * h * 0.9f, LINE);
            }
        } else if ("target".equals(name)) {
            float k = h * 0.8f, m = h * 0.35f;
            // Corner brackets like a focus frame, dot in the middle
            c.drawLine(cx - k, cy - k, cx - k + m, cy - k, LINE);
            c.drawLine(cx - k, cy - k, cx - k, cy - k + m, LINE);
            c.drawLine(cx + k, cy - k, cx + k - m, cy - k, LINE);
            c.drawLine(cx + k, cy - k, cx + k, cy - k + m, LINE);
            c.drawLine(cx - k, cy + k, cx - k + m, cy + k, LINE);
            c.drawLine(cx - k, cy + k, cx - k, cy + k - m, LINE);
            c.drawLine(cx + k, cy + k, cx + k - m, cy + k, LINE);
            c.drawLine(cx + k, cy + k, cx + k, cy + k - m, LINE);
            c.drawCircle(cx, cy, h * 0.12f, FILL);
        } else if ("wifi".equals(name)) {
            for (int i = 1; i <= 3; i++) {
                float rr = h * 0.3f * i;
                r.set(cx - rr, cy + h * 0.6f - rr, cx + rr, cy + h * 0.6f + rr);
                c.drawArc(r, 225, 90, false, LINE);
            }
            c.drawCircle(cx, cy + h * 0.6f, h * 0.1f, FILL);
        } else if ("gear".equals(name)) {
            c.drawCircle(cx, cy, h * 0.5f, LINE);
            c.drawCircle(cx, cy, h * 0.18f, LINE);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
                c.drawLine(cx + dx * h * 0.5f, cy + dy * h * 0.5f, cx + dx * h * 0.82f, cy + dy * h * 0.82f, LINE);
            }
        } else if ("star".equals(name)) {
            for (int i = 0; i < 10; i++) {
                double a = -Math.PI / 2 + Math.PI / 5 * i;
                float rr = i % 2 == 0 ? h * 0.9f : h * 0.38f;
                float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                if (i == 0) {
                    p.moveTo(x, y);
                } else {
                    p.lineTo(x, y);
                }
            }
            p.close();
            c.drawPath(p, LINE);
        } else if ("card".equals(name)) {
            p.moveTo(cx - h * 0.55f, cy - h * 0.8f);
            p.lineTo(cx + h * 0.3f, cy - h * 0.8f);
            p.lineTo(cx + h * 0.6f, cy - h * 0.5f);
            p.lineTo(cx + h * 0.6f, cy + h * 0.8f);
            p.lineTo(cx - h * 0.55f, cy + h * 0.8f);
            p.close();
            c.drawPath(p, LINE);
            for (int i = 0; i < 3; i++) {
                float x = cx - h * 0.3f + i * h * 0.25f;
                c.drawLine(x, cy - h * 0.55f, x, cy - h * 0.25f, LINE);
            }
        } else if ("stack".equals(name)) {
            for (int i = 0; i < 3; i++) {
                float o = (i - 1) * h * 0.28f;
                c.drawRect(cx - h * 0.6f + o, cy - h * 0.45f - o, cx + h * 0.4f + o, cy + h * 0.35f - o, LINE);
            }
        } else if ("timer".equals(name) || "clock".equals(name)) {
            c.drawCircle(cx, cy + h * 0.08f, h * 0.72f, LINE);
            c.drawLine(cx, cy + h * 0.08f, cx, cy - h * 0.35f, LINE);
            c.drawLine(cx, cy + h * 0.08f, cx + h * 0.32f, cy + h * 0.25f, LINE);
            if ("timer".equals(name)) {
                c.drawLine(cx - h * 0.2f, cy - h * 0.85f, cx + h * 0.2f, cy - h * 0.85f, LINE);
            }
        } else if ("layers".equals(name)) {
            for (int i = 0; i < 3; i++) {
                float y = cy - h * 0.45f + i * h * 0.4f;
                p.reset();
                p.moveTo(cx - h * 0.8f, y);
                p.lineTo(cx, y - h * 0.3f);
                p.lineTo(cx + h * 0.8f, y);
                p.lineTo(cx, y + h * 0.3f);
                p.close();
                c.drawPath(p, LINE);
            }
        } else if ("iso".equals(name)) {
            c.drawRect(cx - h * 0.85f, cy - h * 0.5f, cx + h * 0.85f, cy + h * 0.5f, LINE);
            c.drawLine(cx - h * 0.5f, cy - h * 0.25f, cx - h * 0.5f, cy + h * 0.25f, LINE);
            c.drawCircle(cx, cy, h * 0.22f, LINE);
            c.drawCircle(cx + h * 0.5f, cy, h * 0.15f, FILL);
        } else if ("palette".equals(name)) {
            c.drawCircle(cx, cy, h * 0.8f, LINE);
            c.drawCircle(cx - h * 0.3f, cy - h * 0.3f, h * 0.13f, FILL);
            c.drawCircle(cx + h * 0.25f, cy - h * 0.35f, h * 0.13f, FILL);
            c.drawCircle(cx + h * 0.4f, cy + h * 0.15f, h * 0.13f, FILL);
            c.drawCircle(cx - h * 0.35f, cy + h * 0.25f, h * 0.13f, FILL);
        } else if ("meter".equals(name)) {
            c.drawRect(cx - h * 0.85f, cy - h * 0.6f, cx + h * 0.85f, cy + h * 0.6f, LINE);
            c.drawCircle(cx, cy, h * 0.3f, LINE);
            c.drawCircle(cx, cy, h * 0.08f, FILL);
        } else if ("bolt".equals(name)) {
            p.moveTo(cx + h * 0.15f, cy - h * 0.9f);
            p.lineTo(cx - h * 0.45f, cy + h * 0.1f);
            p.lineTo(cx, cy + h * 0.1f);
            p.lineTo(cx - h * 0.15f, cy + h * 0.9f);
            p.lineTo(cx + h * 0.45f, cy - h * 0.15f);
            p.lineTo(cx, cy - h * 0.15f);
            p.close();
            c.drawPath(p, LINE);
        } else if ("eye".equals(name)) {
            r.set(cx - h * 0.9f, cy - h * 0.5f, cx + h * 0.9f, cy + h * 0.5f);
            p.addOval(r, Path.Direction.CW);
            c.drawPath(p, LINE);
            c.drawCircle(cx, cy, h * 0.25f, FILL);
        } else if ("info".equals(name)) {
            c.drawCircle(cx, cy, h * 0.8f, LINE);
            c.drawLine(cx, cy - h * 0.05f, cx, cy + h * 0.45f, LINE);
            c.drawCircle(cx, cy - h * 0.35f, h * 0.08f, FILL);
        } else {
            return false;
        }
        return true;
    }
}
