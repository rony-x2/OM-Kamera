package com.github.ronyx2.omkamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.List;

/** Draws the user interface on top of the live view. Reads everything from {@link Ui}. */
public class OverlayView extends View {
    private Ui ui;
    private float u = 1;
    /** Size unit of the live view display and of menus / Fn grid */
    private float hudU = 1, menuU = 1;
    /**
     * Drawing width in square pixels. The ILCE-6000 shows 640x480 pixels on a 16:9 panel,
     * so the canvas is compressed horizontally and everything is laid out on a wider virtual width.
     */
    private float vw;
    private float pixelAspect = 1f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final android.graphics.Path bolt = new android.graphics.Path();

    public OverlayView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.DEFAULT);
    }

    public void setUi(Ui ui) {
        this.ui = ui;
    }

    private LiveAnalysis analysis;
    private final Paint maskPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    public void setAnalysis(LiveAnalysis a) {
        this.analysis = a;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (ui == null) {
            return;
        }
        // Menus and the Fn grid have their own size, the live view display another one
        Ui.State st = ui.state();
        hudU = Theme.unit(getHeight()) * ui.hudScale();
        statusBottom = 0;
        menuU = Theme.unit(getHeight()) * ui.menuScale();
        u = st == Ui.State.MENU ? menuU : hudU;
        pixelAspect = ui.session().pixelAspect(getWidth(), getHeight());
        vw = getWidth() * pixelAspect;
        c.save();
        c.scale(1f / pixelAspect, 1f);
        CameraSession.Params p = ui.session().current();
        switch (ui.state()) {
            case LIVE:
                drawAnalysis(c);
                drawGrid(c);
                drawMarkers(c);
                drawFocusFrames(c);
                if (ui.levelEnabled()) {
                    drawLevel(c);
                }
                if (ui.infoVisible()) {
                    drawBattery(c);
                    drawStatusChips(c, p);
                    drawExposureBar(c, p);
                    drawHistogram(c);
                    drawWarnings(c, p);
                }
                drawSelfTimer(c);
                drawSequence(c);
                drawProcessing(c);
                break;
            case FN:
            case FN_EDIT:
                // Battery and exposure line keep the live view size, the grid has the menu size
                drawBattery(c);
                drawExposureBar(c, p);
                u = menuU;
                drawFnGrid(c, p);
                u = hudU;
                break;
            case MENU:
                drawMenu(c, p);
                break;
            case AF_MOVE:
                drawAnalysis(c);
                drawFocusMove(c);
                drawExposureBar(c, p);
                break;
            case QUICK: {
                drawAnalysis(c);
                drawGrid(c);
                drawMarkers(c);
                drawFocusFrames(c);
                drawBattery(c);
                drawExposureBar(c, p);
                Setting q = ui.quickSetting();
                if (q != null) {
                    float b = getHeight() - Theme.BAR * u - Theme.PAD * u;
                    drawValueStrip(c, q, p, Theme.PAD * u, b - 64 * u, vw - Theme.PAD * u, b);
                }
                break;
            }
            case MAGNIFY:
                drawMagnifier(c, p);
                break;
            default:
                break;
        }
        drawProgressEdge(c);
        drawMessage(c);
        c.restore();
    }

    // ---- live view ----

    private static final String[] CHIP_SETTINGS = {"drive", "flash", "afmf", "afmode", "afarea", "wb", "metering", "quality"};

    /** Status line at the top: an icon where one exists, otherwise the value as text. */
    /** Lower edge of the status chips (one or two rows), for what is shown below them */
    private float statusBottom;

    /** Top edge for chips and messages below the status line */
    private float belowStatus() {
        float one = Theme.PAD * u + Theme.ICON * u + 4 * u;
        return Math.max(one, statusBottom) + 8 * u;
    }

    private void drawStatusChips(Canvas c, CameraSession.Params p) {
        float pad = Theme.PAD * u;
        float x = pad;
        float y = pad;
        float h = Theme.ICON * u + 4 * u;
        float limit = statusLeft - 6 * u;
        int rowsUsed = 1;
        textSize(Theme.TEXT_SMALL);
        java.util.List<String> chips = new java.util.ArrayList<String>(java.util.Arrays.asList(CHIP_SETTINGS));
        chips.add("_focal");
        for (String id : chips) {
            String value = null, t;
            boolean icon = false;
            if ("_focal".equals(id)) {
                // Current focal length of the lens (follows the zoom ring)
                float focal = ui.session().focalLength();
                if (focal <= 0) {
                    continue;
                }
                t = Math.round(focal) + " mm";
            } else {
                Setting s = Settings.get(id);
                if (s == null || !s.isAvailable(p)) {
                    continue;
                }
                value = s.safeGet(p);
                t = s.currentText(p);
                icon = hasIcon(id, value);
            }
            text.setTypeface(Typeface.DEFAULT_BOLD);
            float w = icon ? h : text.measureText(t) + 2 * pad;
            if (x + w > limit) {
                // Larger display: what does not fit goes into a second row (full width there)
                if (rowsUsed == 2) {
                    break;
                }
                rowsUsed = 2;
                x = pad;
                y += h + 6 * u;
                limit = vw - pad;
                if (x + w > limit) {
                    break;
                }
            }
            roundRect(c, x, y, x + w, y + h, Theme.PANEL);
            if (!icon || !Icons.draw(c, id, value, x + w / 2, y + h / 2, Theme.ICON * u, Theme.TEXT)) {
                drawText(c, t, x + pad, y + h / 2 + Theme.TEXT_SMALL * u * 0.36f, Theme.TEXT);
            }
            text.setTypeface(Typeface.DEFAULT);
            x += w + 6 * u;
        }
        text.setTypeface(Typeface.DEFAULT);
        statusBottom = y + h;
    }

    /** Battery at the top right; WLAN marker left of it while WLAN is switched on in the app. */
    private void drawBattery(Canvas c) {
        int level = ui.batteryLevel();
        float h = Theme.ICON * u + 4 * u;
        float pad = Theme.PAD * u;
        textSize(Theme.TEXT_SMALL);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        String t = level >= 0 ? level + "%" : "?";
        float bodyW = 30 * u;
        float w = bodyW + 6 * u + text.measureText(t) + 2 * pad;
        float r = vw - pad;
        float l = r - w;
        float y = pad;
        roundRect(c, l, y, r, y + h, Theme.PANEL);
        // Battery body with fill level
        float bl = l + pad;
        float bt = y + h / 2 - 8 * u;
        float bb = y + h / 2 + 8 * u;
        int color = level >= 0 && level <= 15 ? Theme.WARN : ui.batteryPlugged() ? Theme.AF_OK : Theme.TEXT;
        outline(c, bl, bt, bl + bodyW - 4 * u, bb, color, 2 * u);
        fill.setColor(color);
        c.drawRect(bl + bodyW - 4 * u, bt + 5 * u, bl + bodyW - 1 * u, bb - 5 * u, fill);
        if (level > 0) {
            float inner = (bodyW - 10 * u) * Math.min(100, level) / 100f;
            c.drawRect(bl + 3 * u, bt + 3 * u, bl + 3 * u + inner, bb - 3 * u, fill);
        }
        drawText(c, t, bl + bodyW + 6 * u, y + h / 2 + Theme.TEXT_SMALL * u * 0.36f, color);
        text.setTypeface(Typeface.DEFAULT);
        if (ui.batteryPlugged()) {
            // Charging: lightning bolt over the battery body
            bolt.reset();
            float cx = bl + (bodyW - 4 * u) / 2, cy = y + h / 2;
            bolt.moveTo(cx + 2 * u, cy - 9 * u);
            bolt.lineTo(cx - 5 * u, cy + 1 * u);
            bolt.lineTo(cx, cy + 1 * u);
            bolt.lineTo(cx - 2 * u, cy + 9 * u);
            bolt.lineTo(cx + 5 * u, cy - 1 * u);
            bolt.lineTo(cx, cy - 1 * u);
            bolt.close();
            fill.setColor(Theme.PANEL_SOLID);
            stroke.setColor(Theme.PANEL_SOLID);
            stroke.setStrokeWidth(3 * u);
            c.drawPath(bolt, stroke);
            fill.setColor(0xFFFFD040);
            c.drawPath(bolt, fill);
        }

        float leftEdge = l;
        if (ui.shotsEnabled() && ui.remainingShots() >= 0) {
            // Remaining pictures with a small memory card symbol
            String n = String.valueOf(ui.remainingShots());
            textSize(Theme.TEXT_SMALL);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            float cw = 13 * u;
            float ww = cw + 6 * u + text.measureText(n) + 2 * pad;
            float cl = leftEdge - 6 * u - ww;
            roundRect(c, cl, y, leftEdge - 6 * u, y + h, Theme.PANEL);
            int shotsColor = ui.remainingShots() < 30 ? Theme.WARN : Theme.TEXT;
            android.graphics.Path card = new android.graphics.Path();
            float ct = y + h / 2 - 9 * u, cb = y + h / 2 + 9 * u, cx0 = cl + pad;
            card.moveTo(cx0, ct + 4 * u);
            card.lineTo(cx0 + 4 * u, ct);
            card.lineTo(cx0 + cw, ct);
            card.lineTo(cx0 + cw, cb);
            card.lineTo(cx0, cb);
            card.close();
            stroke.setColor(shotsColor);
            stroke.setStrokeWidth(2 * u);
            c.drawPath(card, stroke);
            drawText(c, n, cx0 + cw + 6 * u, y + h / 2 + Theme.TEXT_SMALL * u * 0.36f, shotsColor);
            text.setTypeface(Typeface.DEFAULT);
            leftEdge = cl;
            l = cl;
        }
        if (ui.wifiEnabled()) {
            String wl = "WLAN" + (ui.debugEnabled() ? "·DBG" : "") + (ui.mcpEnabled() ? "·MCP" : "");
            // Red while WLAN is switched on but has no connection (yet)
            boolean connected = ui.wifiConnected();
            textSize(Theme.TEXT_SMALL);
            float ww = text.measureText(wl) + 2 * pad;
            roundRect(c, l - 6 * u - ww, y, l - 6 * u, y + h, Theme.PANEL);
            drawText(c, wl, l - 6 * u - ww + pad, y + h / 2 + Theme.TEXT_SMALL * u * 0.36f, connected ? Theme.FN_BLUE : Theme.WARN);
            leftEdge = l - 6 * u - ww;
        }
        if (ui.writing()) {
            // Red dot while pictures are written to the card, gently pulsing
            float dotR = 9 * u;
            float cx = leftEdge - 6 * u - dotR - 4 * u;
            int alpha = 160 + (int) (95 * Math.abs(Math.sin(android.os.SystemClock.uptimeMillis() / 300.0)));
            roundRect(c, cx - dotR - 6 * u, y, cx + dotR + 6 * u, y + h, Theme.PANEL);
            fill.setColor((alpha << 24) | 0xE53935);
            c.drawCircle(cx, y + h / 2, dotR, fill);
            postInvalidateDelayed(80);
            leftEdge = cx - dotR - 6 * u;
        }
        statusLeft = leftEdge;
    }

    /** Left edge of the status block at the top right; the setting chips stop before it */
    private float statusLeft;

    // ---- guides: grid, level, histogram, warnings ----

    private void drawGrid(Canvas c) {
        Ui.Grid g = ui.grid();
        if (g == Ui.Grid.OFF) {
            return;
        }
        android.graphics.Rect v = ui.session().videoArea(getWidth(), getHeight());
        float l = v.left * pixelAspect, r = v.right * pixelAspect, t = v.top, b = v.bottom;
        stroke.setColor(Theme.GUIDE);
        stroke.setStrokeWidth(Math.max(1, 1.2f * u));
        if (g == Ui.Grid.GOLDEN) {
            // Golden section: lines at 0.382 and 0.618 of width and height
            float phi = 0.381966f;
            for (float f : new float[]{phi, 1 - phi}) {
                float x = l + (r - l) * f;
                float y = t + (b - t) * f;
                c.drawLine(x, t, x, b, stroke);
                c.drawLine(l, y, r, y, stroke);
            }
            return;
        }
        int cols = g == Ui.Grid.THIRDS ? 3 : 6;
        int rows = g == Ui.Grid.THIRDS ? 3 : 4;
        for (int i = 1; i < cols; i++) {
            float x = l + (r - l) * i / cols;
            c.drawLine(x, t, x, b, stroke);
        }
        for (int i = 1; i < rows; i++) {
            float y = t + (b - t) * i / rows;
            c.drawLine(l, y, r, y, stroke);
        }
        if (g == Ui.Grid.DIAGONAL) {
            c.drawLine(l, t, r, b, stroke);
            c.drawLine(r, t, l, b, stroke);
        }
    }

    /** Zebra and peaking mask from {@link LiveAnalysis}, stretched over the picture */
    private void drawAnalysis(Canvas c) {
        android.graphics.Bitmap m = analysis != null ? analysis.mask() : null;
        if (m == null || m.isRecycled()) {
            return;
        }
        android.graphics.Rect v = ui.session().videoArea(getWidth(), getHeight());
        rect.set(v.left * pixelAspect, v.top, v.right * pixelAspect, v.bottom);
        c.drawBitmap(m, null, rect, maskPaint);
    }

    /** Centre cross, aspect frame for a later crop and safe zone */
    private void drawMarkers(Canvas c) {
        Ui.Frame fr = ui.frame();
        int safe = ui.safeZone();
        if (!ui.centerMark() && fr == Ui.Frame.OFF && safe == 0) {
            return;
        }
        android.graphics.Rect v = ui.session().videoArea(getWidth(), getHeight());
        float l = v.left * pixelAspect, r = v.right * pixelAspect, t = v.top, b = v.bottom;
        float w = r - l, h = b - t;
        stroke.setStrokeWidth(Math.max(1, 1.5f * u));
        if (fr != Ui.Frame.OFF && w > 0 && h > 0) {
            float fw = w, fh = h;
            if (fr.ratio < w / h) {
                fw = h * fr.ratio;
            } else {
                fh = w / fr.ratio;
            }
            float fl = l + (w - fw) / 2, ft = t + (h - fh) / 2;
            // Shade what the crop removes, then outline the frame
            fill.setColor(0x73000000);
            if (fl > l + 0.5f) {
                c.drawRect(l, t, fl, b, fill);
                c.drawRect(fl + fw, t, r, b, fill);
            }
            if (ft > t + 0.5f) {
                c.drawRect(l, t, r, ft, fill);
                c.drawRect(l, ft + fh, r, b, fill);
            }
            stroke.setColor(0xCCFFFFFF);
            c.drawRect(fl, ft, fl + fw, ft + fh, stroke);
        }
        if (safe > 0) {
            float sw = w * safe / 100f, sh = h * safe / 100f;
            float sl = l + (w - sw) / 2, st = t + (h - sh) / 2;
            stroke.setColor(0x99FFD60A);
            stroke.setPathEffect(new android.graphics.DashPathEffect(new float[]{8 * u, 6 * u}, 0));
            c.drawRect(sl, st, sl + sw, st + sh, stroke);
            stroke.setPathEffect(null);
        }
        if (ui.centerMark()) {
            float cx = l + w / 2, cy = t + h / 2, a = 14 * u;
            stroke.setColor(0xE6FFFFFF);
            c.drawLine(cx - a, cy, cx + a, cy, stroke);
            c.drawLine(cx, cy - a, cx, cy + a, stroke);
        }
    }

    /**
     * Level from the acceleration sensor: a line through the middle that tilts with the camera,
     * green within 1 degree. Also shows when the camera is held upright (portrait).
     */
    private void drawLevel(Canvas c) {
        float roll = ui.roll();
        if (Float.isNaN(roll)) {
            return;
        }
        // Deviation from the nearest of 0, 90, 180, 270 degrees; the quadrant is the orientation
        int quadrant = Math.round(roll / 90f);
        float dev = roll - quadrant * 90f;
        boolean level = Math.abs(dev) < 1f;
        float cx = vw / 2, cy = getHeight() / 2f;
        float len = 150 * u;
        double a = Math.toRadians(-dev);
        float dx = (float) Math.cos(a) * len, dy = (float) Math.sin(a) * len;
        int color = level ? Theme.AF_OK : Theme.TEXT;
        // Fixed reference marks left and right
        stroke.setColor(Theme.GUIDE);
        stroke.setStrokeWidth(2 * u);
        c.drawLine(cx - len - 24 * u, cy, cx - len - 8 * u, cy, stroke);
        c.drawLine(cx + len + 8 * u, cy, cx + len + 24 * u, cy, stroke);
        // Moving line with a gap for the AF frame
        stroke.setColor(color);
        stroke.setStrokeWidth((level ? 3 : 2) * u);
        float gap = 0.32f;
        c.drawLine(cx - dx, cy - dy, cx - dx * gap, cy - dy * gap, stroke);
        c.drawLine(cx + dx * gap, cy + dy * gap, cx + dx, cy + dy, stroke);
        // Pitch: small marker above or below the right end, 1 px per 0.25 degree, limited
        float pitch = ui.pitch();
        if (!Float.isNaN(pitch)) {
            float py = Math.max(-40 * u, Math.min(40 * u, -pitch * 4 * u));
            boolean flat = Math.abs(pitch) < 1f;
            fill.setColor(flat ? Theme.AF_OK : Theme.TEXT);
            c.drawCircle(cx + len + 16 * u, cy + py, 4 * u, fill);
        }
        textSize(Theme.TEXT_SMALL);
        String t = String.format(java.util.Locale.US, "%.1f°", dev).replace('.', ',')
                + (quadrant % 2 != 0 ? "  Hochformat" : "");
        drawText(c, t, cx - text.measureText(t) / 2, cy + 34 * u, color);
    }

    private void drawHistogram(Canvas c) {
        if (ui.histo() == Ui.Histo.OFF) {
            return;
        }
        short[][] h = ui.session().histogram();
        float w = 150 * u, hh = 76 * u;
        float r = vw - Theme.PAD * u;
        float b = getHeight() - Theme.BAR * u - Theme.PAD * u;
        float l = r - w, t = b - hh;
        roundRect(c, l, t, r, b, Theme.PANEL);
        if (h == null) {
            textSize(Theme.TEXT_SMALL);
            drawText(c, "Histogramm …", l + 10 * u, t + hh / 2 + 6 * u, Theme.TEXT_DIM);
            return;
        }
        float pl = l + 6 * u, pr = r - 6 * u, pt = t + 6 * u, pb = b - 6 * u;
        if (ui.histo() == Ui.Histo.RGB && (h[1] == null || h[2] == null || h[3] == null)) {
            short[][] own = analysis != null ? analysis.rgb() : null;
            if (own != null) {
                h = new short[][]{h[0], own[0], own[1], own[2]};
            }
        }
        if (ui.histo() == Ui.Histo.RGB && h[1] != null && h[2] != null && h[3] != null) {
            histLine(c, h[1], pl, pt, pr, pb, Theme.HIST_R);
            histLine(c, h[2], pl, pt, pr, pb, Theme.HIST_G);
            histLine(c, h[3], pl, pt, pr, pb, Theme.HIST_B);
        } else {
            histArea(c, h[0], pl, pt, pr, pb);
        }
        // Clipping markers: red bar at the edge when much is lost in the highlights or shadows
        float hi = share(h[0], false), lo = share(h[0], true);
        fill.setColor(Theme.WARN);
        if (hi > 0.01f) {
            c.drawRect(pr - 3 * u, pt, pr, pb, fill);
        }
        if (lo > 0.05f) {
            c.drawRect(pl, pt, pl + 3 * u, pb, fill);
        }
    }

    /** Share of pixels in the outermost 2 % of the bins (dark or bright end) */
    private static float share(short[] bins, boolean dark) {
        if (bins == null || bins.length == 0) {
            return 0;
        }
        long total = 0, edge = 0;
        int n = Math.max(1, bins.length / 50);
        for (int i = 0; i < bins.length; i++) {
            int v = bins[i] & 0xFFFF;
            total += v;
            if (dark ? i < n : i >= bins.length - n) {
                edge += v;
            }
        }
        return total > 0 ? (float) edge / total : 0;
    }

    private static int histMax(short[] bins) {
        // Scale to the second highest value so a single spike does not flatten everything
        int m1 = 1, m2 = 1;
        for (short s : bins) {
            int v = s & 0xFFFF;
            if (v > m1) {
                m2 = m1;
                m1 = v;
            } else if (v > m2) {
                m2 = v;
            }
        }
        return Math.max(1, m2);
    }

    private void histArea(Canvas c, short[] bins, float l, float t, float r, float b) {
        if (bins == null || bins.length == 0) {
            return;
        }
        int max = histMax(bins);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(l, b);
        for (int i = 0; i < bins.length; i++) {
            float x = l + (r - l) * i / (bins.length - 1);
            float y = b - (b - t) * Math.min(1f, (bins[i] & 0xFFFF) / (float) max);
            path.lineTo(x, y);
        }
        path.lineTo(r, b);
        path.close();
        fill.setColor(0xCCDDE2E8);
        c.drawPath(path, fill);
    }

    private void histLine(Canvas c, short[] bins, float l, float t, float r, float b, int color) {
        if (bins == null || bins.length == 0) {
            return;
        }
        int max = histMax(bins);
        android.graphics.Path path = new android.graphics.Path();
        for (int i = 0; i < bins.length; i++) {
            float x = l + (r - l) * i / (bins.length - 1);
            float y = b - (b - t) * Math.min(1f, (bins[i] & 0xFFFF) / (float) max);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        stroke.setColor(color);
        stroke.setStrokeWidth(1.5f * u);
        c.drawPath(path, stroke);
    }

    /** Short hints above the exposure bar, like the camera's caution messages */
    private void drawWarnings(Canvas c, CameraSession.Params p) {
        if (!ui.warningsEnabled()) {
            return;
        }
        java.util.List<String> w = warnings();
        if (w.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : w) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(s);
        }
        String t = sb.toString();
        textSize(Theme.TEXT_SMALL);
        float tw = text.measureText(t);
        float l = Theme.PAD * u;
        float b = getHeight() - Theme.BAR * u - Theme.PAD * u;
        roundRect(c, l, b - 30 * u, l + tw + 20 * u, b, Theme.PANEL);
        fill.setColor(Theme.ACCENT);
        c.drawRect(l, b - 30 * u, l + 3 * u, b, fill);
        drawText(c, t, l + 12 * u, b - 10 * u, Theme.ACCENT);
    }

    /** Active warnings; also used by the MCP status */
    java.util.List<String> warnings() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        CameraSession s = ui.session();
        double sec = s.shutterSeconds();
        float focal = s.focalLength();
        // Rule of thumb for APS-C: 1 / (1.5 × focal length), at least 1/30 s without lens data;
        // with the app's ISO automatic its own limit
        double limit = focal > 0 ? 1.0 / (1.5 * focal) : 1.0 / 30;
        AutoIso ai = ui.autoIso();
        if (ai != null && ai.mode() != 0) {
            limit = ai.limit();
        }
        if (sec > 0 && sec > limit * 1.01) {
            out.add("Verwacklungsgefahr");
        }
        short[][] h = s.histogram();
        if (h != null && share(h[0], false) > 0.01f) {
            out.add("Lichter überbelichtet");
        }
        int bat = ui.batteryLevel();
        if (bat >= 0 && bat <= 10 && !ui.batteryPlugged()) {
            out.add("Akku fast leer");
        }
        int shots = ui.remainingShots();
        if (shots >= 0 && shots < 30) {
            out.add(shots == 0 ? "Karte voll" : "Karte fast voll");
        }
        return out;
    }

    /** Checks on a scratch canvas whether an icon exists, without drawing it. */
    private boolean hasIcon(String id, String value) {
        return Icons.draw(scratch, id, value, 0, 0, 10, 0);
    }

    private final Canvas scratch = new Canvas(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888));

    // ---- AF frames ----

    /** Focus magnifier: level, position of the magnified area in a small map, hints */
    private void drawMagnifier(Canvas c, CameraSession.Params p) {
        String label = ui.session().magnificationLabel();
        int[] pt = ui.session().magnificationPoint();
        float pad = Theme.PAD * u;
        textSize(Theme.TEXT_LARGE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        String t = "Fokuslupe " + (label != null ? label : "");
        float tw = text.measureText(t);
        roundRect(c, pad, pad, pad + tw + 24 * u, pad + 44 * u, Theme.PANEL);
        drawText(c, t, pad + 12 * u, pad + 32 * u, Theme.ACCENT);
        text.setTypeface(Typeface.DEFAULT);
        if (pt != null) {
            // Map of the whole picture with the magnified part
            float mw = 120 * u, mh = 80 * u;
            float ml = vw - pad - mw, mt = pad;
            roundRect(c, ml, mt, ml + mw, mt + mh, Theme.PANEL);
            outline(c, ml, mt, ml + mw, mt + mh, Theme.GUIDE, 1.5f * u);
            float cx = ml + mw * (pt[0] + 1000) / 2000f;
            float cy = mt + mh * (pt[1] + 1000) / 2000f;
            float factor = 6f;
            if (label != null) {
                try {
                    factor = Float.parseFloat(label.substring(1).replace(',', '.'));
                } catch (NumberFormatException ignored) {
                }
            }
            float hw = Math.max(3 * u, mw / factor / 2), hh = Math.max(3 * u, mh / factor / 2);
            fill.setColor(0x55F0A030);
            c.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, fill);
            outline(c, cx - hw, cy - hh, cx + hw, cy + hh, Theme.ACCENT, 1.5f * u);
        }
        String hint = "Pfeile: Ausschnitt  ·  Löschen: Mitte  ·  Mitte: nächste Stufe  ·  MENU: Ende";
        textSize(Theme.TEXT_SMALL);
        float hw = text.measureText(hint) + 24 * u;
        float hb = getHeight() - Theme.BAR * u - pad;
        roundRect(c, (vw - hw) / 2, hb - 30 * u, (vw + hw) / 2, hb, Theme.PANEL);
        drawText(c, hint, (vw - hw) / 2 + 12 * u, hb - 10 * u, Theme.TEXT);
        drawExposureBar(c, p);
    }

    /** Progress of a running app sequence, centred under the status line */
    /** Thin progress line along the top edge of the display, in every view, while the app processes */
    private void drawProgressEdge(Canvas c) {
        Processor pr = ui.processor();
        if (pr == null || !pr.busy()) {
            return;
        }
        float h = 4 * u;
        float f = pr.fraction();
        fill.setColor(0x334DA3FF);
        c.drawRect(0, 0, vw, h, fill);
        fill.setColor(0xE64DA3FF);
        c.drawRect(0, 0, vw * f, h, fill);
        // A soft light running along the filled part: the job is alive
        float t = (android.os.SystemClock.uptimeMillis() % 1600) / 1600f;
        float x = vw * f * t;
        fill.setColor(0x80FFFFFF);
        c.drawRect(Math.max(0, x - 24 * u), 0, x, h, fill);
        postInvalidateDelayed(120);
    }

    /** Small chip at the bottom while the app converts or combines RAW files */
    private void drawProcessing(Canvas c) {
        Processor pr = ui.processor();
        String t = pr != null ? pr.progress() : null;
        if (t == null) {
            return;
        }
        textSize(Theme.TEXT_SMALL);
        float w = text.measureText(t) + 36 * u;
        float b = getHeight() - Theme.BAR * u - Theme.PAD * u - 90 * u;
        float l = (vw - w) / 2;
        roundRect(c, l, b - 28 * u, l + w, b, Theme.PANEL);
        // Slow breathing dot: working, not recording
        int alpha = 120 + (int) (100 * Math.abs(Math.sin(android.os.SystemClock.uptimeMillis() / 700.0)));
        fill.setColor((alpha << 24) | 0x4DA3FF);
        c.drawCircle(l + 14 * u, b - 14 * u, 5 * u, fill);
        drawText(c, t, l + 26 * u, b - 8 * u, Theme.TEXT);
        postInvalidateDelayed(500);
    }

    private void drawSequence(Canvas c) {
        Sequencer q = ui.sequencer();
        String t = q != null ? q.status() : null;
        if (t == null) {
            if (q != null && q.mode() != Sequencer.Mode.OFF && ui.infoVisible()) {
                // Selected but not running: small hint which sequence the shutter starts
                String h = q.mode().label;
                textSize(Theme.TEXT_SMALL);
                float w = text.measureText(h) + 24 * u;
                float top = belowStatus();
                roundRect(c, (vw - w) / 2, top, (vw + w) / 2, top + 28 * u, Theme.PANEL);
                drawText(c, h, (vw - w) / 2 + 12 * u, top + 20 * u, Theme.ACCENT);
            }
            return;
        }
        textSize(Theme.TEXT_NORMAL);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        float w = text.measureText(t) + 40 * u;
        float top = belowStatus();
        roundRect(c, (vw - w) / 2, top, (vw + w) / 2, top + 38 * u, Theme.PANEL_SOLID);
        // Pulsing dot like the recording lamp
        int alpha = 140 + (int) (115 * Math.abs(Math.sin(android.os.SystemClock.uptimeMillis() / 400.0)));
        fill.setColor((alpha << 24) | 0xE53935);
        c.drawCircle((vw - w) / 2 + 18 * u, top + 19 * u, 6 * u, fill);
        drawText(c, t, (vw - w) / 2 + 30 * u, top + 27 * u, Theme.TEXT);
        text.setTypeface(Typeface.DEFAULT);
        postInvalidateDelayed(250);
    }

    /** Countdown of the running self-timer, large in the middle with a ring that runs down */
    private void drawSelfTimer(Canvas c) {
        int left = ui.session().selfTimerRemaining();
        if (left <= 0) {
            return;
        }
        float cx = vw / 2;
        float cy = getHeight() / 2f;
        float r = 72 * u;
        fill.setColor(Theme.PANEL);
        c.drawCircle(cx, cy, r, fill);
        // The ring shows the part of the current second that is left
        float frac = (System.currentTimeMillis() % 1000) / 1000f;
        stroke.setColor(Theme.ACCENT);
        stroke.setStrokeWidth(5 * u);
        android.graphics.RectF arc = new android.graphics.RectF(cx - r + 4 * u, cy - r + 4 * u, cx + r - 4 * u, cy + r - 4 * u);
        c.drawArc(arc, -90, 360 * (1 - frac), false, stroke);
        textSize(Theme.TEXT_LARGE * 3);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        String t = String.valueOf(left);
        drawText(c, t, cx - text.measureText(t) / 2, cy + Theme.TEXT_LARGE * 3 * u * 0.36f, Theme.TEXT);
        text.setTypeface(Typeface.DEFAULT);
        postInvalidateDelayed(100);
    }

    /** Moving the flexible spot: range, spot and a short hint */
    private void drawFocusMove(Canvas c) {
        List<CameraSession.FocusFrame> frames = ui.session().focusFrames(getWidth(), getHeight());
        int last = -1;
        for (CameraSession.FocusFrame f : frames) {
            if (f.enabled && f.index > last) {
                last = f.index;
            }
        }
        for (CameraSession.FocusFrame f : frames) {
            if (f.index == 1 && last > 1) {
                // Movable range, dashed look by short corners
                frame(c, f, 0x66FFFFFF, 1.5f * u, 0.06f);
            } else if (f.index == last) {
                frame(c, f, Theme.ACCENT, 3 * u, 0.5f);
                // Arrows around the spot show that it can be moved
                float cx = (f.rect.left + f.rect.right) / 2f * pixelAspect;
                float cy = (f.rect.top + f.rect.bottom) / 2f;
                float hw = (f.rect.right - f.rect.left) / 2f * pixelAspect + 12 * u;
                float hh = (f.rect.bottom - f.rect.top) / 2f + 12 * u;
                arrow(c, cx, cy - hh, 0, -1);
                arrow(c, cx, cy + hh, 0, 1);
                arrow(c, cx - hw, cy, -1, 0);
                arrow(c, cx + hw, cy, 1, 0);
            }
        }
        String hint = "Pfeile: verschieben  ·  Löschen: Mitte  ·  Mitteltaste: fertig";
        textSize(Theme.TEXT_SMALL);
        float w = text.measureText(hint) + 24 * u;
        float l = (vw - w) / 2;
        float t = Theme.PAD * u;
        roundRect(c, l, t, l + w, t + 32 * u, Theme.PANEL);
        drawText(c, hint, l + 12 * u, t + 22 * u, Theme.TEXT);
    }

    /** Small filled triangle pointing in direction (dx, dy) */
    private void arrow(Canvas c, float x, float y, int dx, int dy) {
        float s = 7 * u;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x + dx * s, y + dy * s);
        path.lineTo(x - dy * s - dx * s * 0.2f, y + dx * s - dy * s * 0.2f);
        path.lineTo(x + dy * s - dx * s * 0.2f, y - dx * s - dy * s * 0.2f);
        path.close();
        fill.setColor(Theme.ACCENT);
        c.drawPath(path, fill);
    }

    private void drawFocusFrames(Canvas c) {
        if (!ui.framesEnabled()) {
            return;
        }
        List<CameraSession.FocusFrame> frames = ui.session().focusFrames(getWidth(), getHeight());
        if (frames.isEmpty()) {
            return;
        }
        int status = ui.focusStatus();
        int[] focused = ui.focusIndices();
        boolean warn = status == 2 || ui.focusResult() == Ui.FocusResult.FAILED;
        boolean succeeded = ui.focusResult() == Ui.FocusResult.OK;
        // AF-S reports the focused frames; AF-C (status 4) reports none, then the active frames turn green
        boolean byIndex = (status == 1 || status == 4) && focused.length > 0;
        boolean locked = byIndex || succeeded;

        // Single frames (index > 0) that are active. With many of them (wide, zone) only the area is shown.
        int enabled = 0;
        for (CameraSession.FocusFrame f : frames) {
            if (f.index != 0 && f.enabled) {
                enabled++;
            }
        }
        // Flexible spot: index 1 is the range the spot can move in, the last frame is the spot itself
        String mode = ui.session().focusAreaMode();
        boolean spotMode = mode != null && (mode.contains("flex") || mode.equals("spot") || mode.equals("local"));
        int spotIndex = -1;
        if (spotMode) {
            for (CameraSession.FocusFrame f : frames) {
                if (f.enabled && f.index > spotIndex) {
                    spotIndex = f.index;
                }
            }
            enabled = 1;
        }
        for (CameraSession.FocusFrame f : frames) {
            boolean active = spotMode ? f.index == spotIndex : f.index != 0 ? f.enabled && enabled == 1 : enabled != 1;
            boolean isFocused = byIndex ? contains(focused, f.index) : succeeded && active;
            if (isFocused) {
                frame(c, f, Theme.AF_OK, 3 * u, f.index == 0 ? 0.18f : 0.5f);
            } else if (!locked) {
                if (spotMode) {
                    if (f.index == spotIndex) {
                        frame(c, f, warn ? Theme.AF_WARN : Theme.AF_IDLE, 2.5f * u, 0.5f);
                    }
                } else if (f.index == 0 && enabled != 1) {
                    frame(c, f, warn ? Theme.AF_WARN : 0x88FFFFFF, 2 * u, 0.12f);
                } else if (f.index != 0 && f.enabled && enabled == 1) {
                    frame(c, f, warn ? Theme.AF_WARN : Theme.AF_IDLE, 2.5f * u, 0.5f);
                }
            }
        }
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    /** Frame as corner brackets; share is the length of a bracket relative to the shorter side. */
    private void frame(Canvas c, CameraSession.FocusFrame f, int color, float width, float share) {
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        // Frames come in real screen pixels; the canvas works in square pixels
        float l = f.rect.left * pixelAspect, t = f.rect.top, r = f.rect.right * pixelAspect, b = f.rect.bottom;
        float len = Math.max(4 * u, Math.min(r - l, b - t) * share);
        c.drawLine(l, t, l + len, t, stroke);
        c.drawLine(l, t, l, t + len, stroke);
        c.drawLine(r, t, r - len, t, stroke);
        c.drawLine(r, t, r, t + len, stroke);
        c.drawLine(l, b, l + len, b, stroke);
        c.drawLine(l, b, l, b - len, stroke);
        c.drawLine(r, b, r - len, b, stroke);
        c.drawLine(r, b, r, b - len, stroke);
    }

    /**
     * Exposure bar: mode, shutter speed, aperture, exposure compensation, ISO.
     * Values that a dial changes in the current mode are underlined; in P the
     * program shift moves shutter speed and aperture together.
     */
    private void drawExposureBar(Canvas c, CameraSession.Params p) {
        float h = Theme.BAR * u;
        float top = getHeight() - h;
        fill.setColor(Theme.PANEL);
        c.drawRect(0, top, vw, getHeight(), fill);

        // Mode badge
        String mode = ui.modeName();
        textSize(Theme.TEXT_LARGE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        float bw = Math.max(h - 12 * u, text.measureText(mode) + 16 * u);
        roundRect(c, 8 * u, top + 6 * u, 8 * u + bw, getHeight() - 6 * u, Theme.ACCENT);
        drawText(c, mode, 8 * u + (bw - text.measureText(mode)) / 2, getHeight() - 17 * u, Theme.ACCENT_TEXT);
        if (ui.aeLocked()) {
            // Exposure held: AEL badge next to the mode, like the camera's asterisk
            textSize(Theme.TEXT_SMALL);
            float aw = text.measureText("AEL") + 12 * u;
            float al = 14 * u + bw;
            outline(c, al, top + 12 * u, al + aw, getHeight() - 12 * u, Theme.ACCENT, 2 * u);
            drawText(c, "AEL", al + 6 * u, getHeight() - 20 * u, Theme.ACCENT);
            textSize(Theme.TEXT_LARGE);
            bw += aw + 6 * u;
        }

        Ui.DialRole d1 = ui.dial1Role();
        Ui.DialRole d2 = ui.dial2Role();
        boolean shift = d1 == Ui.DialRole.SHIFT;
        String[] items = {
            ui.session().shutterText(),
            ui.session().apertureText(),
            valueOf("ev", p),
            "ISO " + valueOf("iso", p) + (ui.autoIso() != null && ui.autoIso().working() ? " A" : ""),
        };
        boolean[] active = {
            d1 == Ui.DialRole.SHUTTER || d2 == Ui.DialRole.SHUTTER || shift,
            d1 == Ui.DialRole.APERTURE || d2 == Ui.DialRole.APERTURE || shift,
            d1 == Ui.DialRole.EV || d2 == Ui.DialRole.EV,
            false,
        };
        float left = 16 * u + bw;
        float slot = (vw - left) / items.length;
        for (int i = 0; i < items.length; i++) {
            float tw = text.measureText(items[i]);
            float x = left + slot * i + (slot - tw) / 2;
            drawText(c, items[i], x, getHeight() - 17 * u, Theme.TEXT);
            if (active[i]) {
                fill.setColor(Theme.ACCENT);
                c.drawRect(x, getHeight() - 10 * u, x + tw, getHeight() - 7 * u, fill);
            }
        }
        text.setTypeface(Typeface.DEFAULT);
    }

    private String valueOf(String id, CameraSession.Params p) {
        Setting s = Settings.get(id);
        return s == null ? "–" : s.currentText(p);
    }

    // ---- Fn grid ----

    private void drawFnGrid(Canvas c, CameraSession.Params p) {
        float pad = Theme.PAD * u;
        float gap = 4 * u;
        float cellH = Theme.CELL_H * u;
        // Above the exposure line, which has the live view size
        float bottom = getHeight() - Theme.BAR * hudU - pad;
        FnLayout fl = ui.fnLayout();
        int n = fl.size();
        int rows = fl.rows(), cols = fl.cols();
        float top = bottom - rows * cellH - (rows - 1) * gap;
        // Cells as wide as in a row of six; fewer functions stand centred
        float cellW = (vw - 2 * pad - 5 * gap) / 6;

        for (int i = 0; i < n; i++) {
            int r = i / cols;
            int col = i % cols;
            int inRow = Math.min(cols, n - r * cols);
            float rowW = inRow * cellW + (inRow - 1) * gap;
            float l = (vw - rowW) / 2 + col * (cellW + gap);
            float t = top + r * (cellH + gap);
            Setting s = fl.visibleSetting(i);
            boolean available = s != null && s.isAvailable(p);
            boolean selected = i == ui.fnIndex();
            boolean editing = selected && ui.state() == Ui.State.FN_EDIT;

            roundRect(c, l, t, l + cellW, t + cellH, editing ? Theme.ACCENT : available ? Theme.CELL : Theme.CELL_DISABLED);
            if (selected && !editing) {
                outline(c, l, t, l + cellW, t + cellH, Theme.ACCENT, 3 * u);
            }
            if (s == null) {
                continue;
            }
            int labelColor = editing ? Theme.ACCENT_TEXT : available ? Theme.TEXT_DIM : Theme.TEXT_DISABLED;
            int valueColor = editing ? Theme.ACCENT_TEXT : available ? Theme.TEXT : Theme.TEXT_DISABLED;
            textSize(Theme.TEXT_SMALL);
            drawFitted(c, s.shortLabel, l + 6 * u, t + 22 * u, cellW - 12 * u, labelColor);
            String value = available ? s.safeGet(p) : null;
            float iconSize = Theme.ICON * 1.15f * u;
            if (available && Icons.draw(c, s.id, value, l + cellW / 2, t + cellH - iconSize / 2 - 6 * u, iconSize, valueColor)) {
                // Icon replaces the value text
            } else {
                textSize(Theme.TEXT_NORMAL);
                text.setTypeface(Typeface.DEFAULT_BOLD);
                drawFitted(c, available ? s.currentText(p) : "–", l + 6 * u, t + cellH - 14 * u, cellW - 12 * u, valueColor);
                text.setTypeface(Typeface.DEFAULT);
            }
        }

        // Value strip above the grid while editing
        if (ui.state() == Ui.State.FN_EDIT) {
            Setting s = ui.fnLayout().visibleSetting(ui.fnIndex());
            if (s != null) {
                drawValueStrip(c, s, p, pad, top - 60 * u - gap, vw - pad, top - gap);
            }
        }
    }

    /** All values of a setting in one line, the current one highlighted, scrolled to stay visible. */
    private void drawValueStrip(Canvas c, Setting s, CameraSession.Params p, float l, float t, float r, float b) {
        roundRect(c, l, t, r, b, Theme.PANEL_SOLID);
        List<String> values = s.safeValues(p);
        String current = s.safeGet(p);
        int cur = Math.max(0, values.indexOf(current));
        textSize(Theme.TEXT_SMALL);
        drawText(c, s.label, l + 10 * u, t + 20 * u, Theme.TEXT_DIM);
        String pos = (cur + 1) + "/" + values.size();
        drawText(c, pos, r - 10 * u - text.measureText(pos), t + 20 * u, Theme.TEXT_DIM);

        textSize(Theme.TEXT_NORMAL);
        float gap = 18 * u;
        float base = b - 12 * u;
        float[] widths = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            text.setTypeface(i == cur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            widths[i] = text.measureText(s.display(p, values.get(i)));
        }
        // Start so that the current value sits in the middle of the strip
        float mid = (l + r) / 2;
        float x = mid - widths[cur] / 2;
        for (int i = cur - 1; i >= 0; i--) {
            x -= widths[i] + gap;
        }
        c.save();
        c.clipRect(l + 6 * u, t, r - 6 * u, b);
        for (int i = 0; i < values.size(); i++) {
            String v = s.display(p, values.get(i));
            if (i == cur) {
                roundRect(c, x - 8 * u, base - Theme.TEXT_NORMAL * u - 2 * u, x + widths[i] + 8 * u, base + 7 * u, Theme.ACCENT);
                text.setTypeface(Typeface.DEFAULT_BOLD);
                drawText(c, v, x, base, Theme.ACCENT_TEXT);
            } else {
                text.setTypeface(Typeface.DEFAULT);
                drawText(c, v, x, base, Theme.TEXT_DIM);
            }
            x += widths[i] + gap;
        }
        c.restore();
        text.setTypeface(Typeface.DEFAULT);
    }

    // ---- menu ----

    private void drawMenu(Canvas c, CameraSession.Params p) {
        fill.setColor(Theme.PANEL_SOLID);
        c.drawRect(0, 0, vw, getHeight(), fill);

        float tabW = 220 * u;
        float rowH = 44 * u;
        float pad = Theme.PAD * u;
        int tabColor = Theme.TAB_COLORS[ui.tab() % Theme.TAB_COLORS.length];
        // The list side takes on the colour of the tab, very lightly
        fill.setColor((tabColor & 0x00FFFFFF) | 0x18000000);
        c.drawRect(tabW + pad, 0, vw, getHeight(), fill);
        float iconS = 22 * u;

        // Tabs
        float tabH = Math.min(rowH + 4 * u, (getHeight() - 2 * pad) / Ui.TABS.length);
        for (int i = 0; i < Ui.TABS.length; i++) {
            float t = pad + i * tabH;
            boolean sel = i == ui.tab();
            int color = Theme.TAB_COLORS[i % Theme.TAB_COLORS.length];
            if (sel) {
                roundRect(c, pad, t, tabW, t + tabH - 4 * u, ui.focus() == Ui.MenuFocus.TABS ? 0x55FFFFFF : 0x33FFFFFF);
            }
            fill.setColor(color);
            c.drawRect(pad, t + 6 * u, pad + 5 * u, t + tabH - 10 * u, fill);
            float tx = pad + 14 * u;
            if (MenuIcons.draw(c, MenuIcons.forTab(Ui.TABS[i]), tx + iconS / 2, t + (tabH - 4 * u) / 2, iconS,
                    sel ? color : Theme.TEXT_DIM)) {
                tx += iconS + 8 * u;
            }
            text.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            // Long names like "Belichtung/Farbe" get a slightly smaller font instead of "…"
            float size = Theme.TEXT_NORMAL;
            textSize(size);
            while (size > 14 && text.measureText(Ui.TABS[i]) > tabW - tx - 4 * u) {
                size -= 1;
                textSize(size);
            }
            drawFitted(c, Ui.TABS[i], tx, t + tabH - 17 * u, tabW - tx - 4 * u, sel ? Theme.TEXT : Theme.TEXT_DIM);
        }
        text.setTypeface(Typeface.DEFAULT);
        stroke.setColor(Theme.LINE);
        stroke.setStrokeWidth(1);
        c.drawLine(tabW + pad, pad, tabW + pad, getHeight() - pad, stroke);

        float left = tabW + 2 * pad;
        float right = vw - pad - 6 * u;
        float top = pad;

        // Title of an open sub list, like the camera's sub pages
        String subTitle = ui.subTitle();
        if (subTitle != null) {
            textSize(Theme.TEXT_SMALL);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            int color = Theme.TAB_COLORS[ui.tab() % Theme.TAB_COLORS.length];
            drawText(c, "‹  " + subTitle, left + 6 * u, top + 22 * u, color);
            text.setTypeface(Typeface.DEFAULT);
            top += 34 * u;
        }

        // Rows
        List<Ui.Row> rows = ui.rows();
        int visible = (int) ((getHeight() - top - pad) / rowH);
        int first = Math.max(0, Math.min(ui.row() - visible / 2, rows.size() - visible));
        for (int i = first; i < rows.size() && i < first + visible; i++) {
            Ui.Row row = rows.get(i);
            float t = top + (i - first) * rowH;
            if (row.header) {
                // Group header with a thin line, in the colour of the tab
                textSize(Theme.TEXT_SMALL);
                text.setTypeface(Typeface.DEFAULT_BOLD);
                int color = Theme.TAB_COLORS[ui.tab() % Theme.TAB_COLORS.length];
                float hx = left + 6 * u;
                float hs = 18 * u;
                if (MenuIcons.draw(c, MenuIcons.forHeading(row.label), hx + hs / 2, t + rowH - 20 * u, hs, color)) {
                    hx += hs + 7 * u;
                }
                drawText(c, row.label, hx, t + rowH - 14 * u, color);
                float tw = text.measureText(row.label);
                stroke.setColor(0x33FFFFFF);
                stroke.setStrokeWidth(1);
                c.drawLine(hx + 10 * u + tw, t + rowH - 20 * u, right, t + rowH - 20 * u, stroke);
                text.setTypeface(Typeface.DEFAULT);
                continue;
            }
            boolean sel = i == ui.row() && ui.focus() != Ui.MenuFocus.TABS;
            boolean available = row.setting == null || row.setting.isAvailable(p);
            boolean passive = row.setting == null && row.action == null && row.picker == null && row.sub == null;
            if (sel) {
                roundRect(c, left, t + 2 * u, right, t + rowH - 2 * u, ui.focus() == Ui.MenuFocus.EDIT ? 0x44F0A030 : 0x33FFFFFF);
            }
            textSize(Theme.TEXT_NORMAL);
            int labelColor = !available ? Theme.TEXT_DISABLED : passive ? Theme.TEXT_DIM : Theme.TEXT;
            String value = row.setting != null ? (available ? row.setting.currentText(p) : "nicht verfügbar") : row.info;
            if (row.sub != null) {
                value = (value != null && value.length() > 0 ? value + "  " : "") + "›";
            }
            boolean editing = sel && ui.focus() == Ui.MenuFocus.EDIT;
            if (editing && value != null) {
                value = "‹ " + value + " ›";
            }
            float valueW = 0;
            if (value != null && value.length() > 0) {
                text.setTypeface(editing ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                valueW = Math.min(text.measureText(value), (right - left) * 0.55f);
                int vc = editing ? Theme.ACCENT : available ? Theme.TEXT_DIM : Theme.TEXT_DISABLED;
                drawFitted(c, value, right - 10 * u - valueW, t + rowH - 14 * u, valueW + 1, vc);
                text.setTypeface(Typeface.DEFAULT);
                // Icon of the current value next to it, where the setting has one (drive, WB …)
                if (row.setting != null && available) {
                    float is = 24 * u;
                    if (Icons.draw(c, row.setting.id, row.setting.safeGet(p), right - 18 * u - valueW - is / 2,
                            t + rowH / 2, is, vc)) {
                        valueW += is + 8 * u;
                    }
                }
            }
            drawFitted(c, row.label, left + 10 * u, t + rowH - 13 * u, right - left - valueW - 34 * u, labelColor);
        }

        // Scroll indicator when the list is longer than the screen
        if (rows.size() > visible) {
            float trackT = top, trackB = getHeight() - pad;
            float th = (trackB - trackT) * visible / rows.size();
            float ty = trackT + (trackB - trackT - th) * first / Math.max(1, rows.size() - visible);
            fill.setColor(0x33FFFFFF);
            c.drawRect(vw - 4 * u, trackT, vw - 1 * u, trackB, fill);
            fill.setColor(Theme.ACCENT);
            c.drawRect(vw - 4 * u, ty, vw - 1 * u, ty + th, fill);
        }

        if (ui.focus() == Ui.MenuFocus.PICK) {
            drawPickList(c, left + 40 * u, pad + 10 * u, right - 10 * u, getHeight() - pad - 10 * u, rowH);
        }
    }

    private void drawPickList(Canvas c, float l, float t, float r, float b, float rowH) {
        roundRect(c, l, t, r, b, 0xF0181C21);
        outline(c, l, t, r, b, Theme.FN_BLUE, 2 * u);
        List<Setting> list = ui.pickList();
        int count = list.size() + 1;
        int visible = (int) ((b - t - 10 * u) / rowH);
        int first = Math.max(0, Math.min(ui.pickIndex() - visible / 2, count - visible));
        for (int i = first; i < count && i < first + visible; i++) {
            float y = t + 5 * u + (i - first) * rowH;
            boolean sel = i == ui.pickIndex();
            if (sel) {
                roundRect(c, l + 6 * u, y + 2 * u, r - 6 * u, y + rowH - 2 * u, Theme.ACCENT);
            }
            String label = i < list.size() ? list.get(i).label : "(leer)";
            textSize(Theme.TEXT_NORMAL);
            drawText(c, label, l + 16 * u, y + rowH - 13 * u, sel ? Theme.ACCENT_TEXT : Theme.TEXT);
        }
    }

    // ---- message ----

    private void drawMessage(Canvas c) {
        String m = ui.message();
        if (m == null) {
            return;
        }
        textSize(Theme.TEXT_NORMAL);
        float w = text.measureText(m) + 30 * u;
        float l = (vw - w) / 2;
        float t = ui.state() == Ui.State.LIVE ? Math.max(50 * u, belowStatus()) : 50 * u;
        roundRect(c, l, t, l + w, t + 36 * u, ui.messageWarn() ? 0xEE5A2018 : Theme.PANEL_SOLID);
        drawText(c, m, l + 15 * u, t + 24 * u, ui.messageWarn() ? 0xFFFFD0C8 : Theme.TEXT);
        // Hide the message again
        postInvalidateDelayed(2600);
    }

    // ---- helpers ----

    private void textSize(float size) {
        text.setTextSize(size * u);
    }

    private void drawText(Canvas c, String s, float x, float y, int color) {
        text.setColor(color);
        c.drawText(s, x, y, text);
    }

    /** Draws text and shortens it with "…" if it does not fit. */
    private void drawFitted(Canvas c, String s, float x, float y, float maxW, int color) {
        String t = s;
        if (text.measureText(t) > maxW) {
            while (t.length() > 1 && text.measureText(t + "…") > maxW) {
                t = t.substring(0, t.length() - 1);
            }
            t = t + "…";
        }
        drawText(c, t, x, y, color);
    }

    private void roundRect(Canvas c, float l, float t, float r, float b, int color) {
        fill.setColor(color);
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, Theme.RADIUS * u, Theme.RADIUS * u, fill);
    }

    private void outline(Canvas c, float l, float t, float r, float b, int color, float width) {
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, Theme.RADIUS * u, Theme.RADIUS * u, stroke);
    }
}
