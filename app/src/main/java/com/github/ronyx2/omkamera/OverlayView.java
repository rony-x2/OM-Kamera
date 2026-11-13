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

    @Override
    protected void onDraw(Canvas c) {
        if (ui == null) {
            return;
        }
        u = Theme.unit(getHeight());
        pixelAspect = ui.session().pixelAspect(getWidth(), getHeight());
        vw = getWidth() * pixelAspect;
        c.save();
        c.scale(1f / pixelAspect, 1f);
        CameraSession.Params p = ui.session().current();
        switch (ui.state()) {
            case LIVE:
                drawFocusFrames(c);
                if (ui.infoVisible()) {
                    drawStatusChips(c, p);
                    drawBattery(c);
                    drawExposureBar(c, p);
                }
                drawSelfTimer(c);
                break;
            case FN:
            case FN_EDIT:
                drawBattery(c);
                drawExposureBar(c, p);
                drawFnGrid(c, p);
                break;
            case MENU:
                drawMenu(c, p);
                break;
            case AF_MOVE:
                drawFocusMove(c);
                drawExposureBar(c, p);
                break;
            default:
                break;
        }
        drawMessage(c);
        c.restore();
    }

    // ---- live view ----

    private static final String[] CHIP_SETTINGS = {"drive", "flash", "afmf", "afmode", "afarea", "wb", "metering", "quality"};

    /** Status line at the top: an icon where one exists, otherwise the value as text. */
    private void drawStatusChips(Canvas c, CameraSession.Params p) {
        float x = Theme.PAD * u;
        float y = Theme.PAD * u;
        float h = Theme.ICON * u + 4 * u;
        textSize(Theme.TEXT_SMALL);
        for (String id : CHIP_SETTINGS) {
            Setting s = Settings.get(id);
            if (s == null || !s.isAvailable(p)) {
                continue;
            }
            String value = s.safeGet(p);
            String t = s.currentText(p);
            float w = hasIcon(id, value) ? h : text.measureText(t) + 2 * Theme.PAD * u;
            if (x + w > vw - 230 * u) {
                break;
            }
            roundRect(c, x, y, x + w, y + h, Theme.PANEL);
            if (!Icons.draw(c, id, value, x + w / 2, y + h / 2, Theme.ICON * u, Theme.TEXT)) {
                text.setTypeface(Typeface.DEFAULT_BOLD);
                drawText(c, t, x + Theme.PAD * u, y + h / 2 + Theme.TEXT_SMALL * u * 0.36f, Theme.TEXT);
                text.setTypeface(Typeface.DEFAULT);
            }
            x += w + 6 * u;
        }
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
        if (ui.wifiEnabled()) {
            String wl = "WLAN" + (ui.debugEnabled() ? "·DBG" : "") + (ui.mcpEnabled() ? "·MCP" : "");
            textSize(Theme.TEXT_SMALL);
            float ww = text.measureText(wl) + 2 * pad;
            roundRect(c, l - 6 * u - ww, y, l - 6 * u, y + h, Theme.PANEL);
            drawText(c, wl, l - 6 * u - ww + pad, y + h / 2 + Theme.TEXT_SMALL * u * 0.36f, Theme.TAB_COLORS[5]);
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
        }
    }

    /** Checks on a scratch canvas whether an icon exists, without drawing it. */
    private boolean hasIcon(String id, String value) {
        return Icons.draw(scratch, id, value, 0, 0, 10, 0);
    }

    private final Canvas scratch = new Canvas(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888));

    // ---- AF frames ----

    /** Countdown of the running self-timer, large in the middle with a ring that runs down */
    private void drawSelfTimer(Canvas c) {
        int left = ui.session().selfTimerRemaining();
        if (left <= 0) {
            return;
        }
        float cx = vw / 2;
        float cy = getHeight() / 2f;
        float r = 52 * u;
        fill.setColor(Theme.PANEL);
        c.drawCircle(cx, cy, r, fill);
        // The ring shows the part of the current second that is left
        float frac = (System.currentTimeMillis() % 1000) / 1000f;
        stroke.setColor(Theme.ACCENT);
        stroke.setStrokeWidth(4 * u);
        android.graphics.RectF arc = new android.graphics.RectF(cx - r + 4 * u, cy - r + 4 * u, cx + r - 4 * u, cy + r - 4 * u);
        c.drawArc(arc, -90, 360 * (1 - frac), false, stroke);
        textSize(Theme.TEXT_LARGE * 2);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        String t = String.valueOf(left);
        drawText(c, t, cx - text.measureText(t) / 2, cy + Theme.TEXT_LARGE * 2 * u * 0.36f, Theme.TEXT);
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
            "ISO " + valueOf("iso", p),
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
        float bottom = getHeight() - Theme.BAR * u - pad;
        float top = bottom - FnLayout.ROWS * cellH - (FnLayout.ROWS - 1) * gap;
        float cellW = (vw - 2 * pad - (FnLayout.COLS - 1) * gap) / FnLayout.COLS;

        for (int i = 0; i < FnLayout.SIZE; i++) {
            int r = i / FnLayout.COLS;
            int col = i % FnLayout.COLS;
            float l = pad + col * (cellW + gap);
            float t = top + r * (cellH + gap);
            Setting s = ui.fnLayout().get(i);
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
            Setting s = ui.fnLayout().get(ui.fnIndex());
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

        float tabW = 160 * u;
        float rowH = 46 * u;
        float pad = Theme.PAD * u;

        // Tabs
        for (int i = 0; i < Ui.TABS.length; i++) {
            float t = pad + i * (rowH + 4 * u);
            boolean sel = i == ui.tab();
            int color = Theme.TAB_COLORS[i % Theme.TAB_COLORS.length];
            if (sel) {
                roundRect(c, pad, t, tabW, t + rowH, ui.focus() == Ui.MenuFocus.TABS ? 0x55FFFFFF : 0x33FFFFFF);
            }
            fill.setColor(color);
            c.drawRect(pad, t + 6 * u, pad + 5 * u, t + rowH - 6 * u, fill);
            textSize(Theme.TEXT_NORMAL);
            text.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            drawText(c, Ui.TABS[i], pad + 14 * u, t + rowH - 13 * u, sel ? Theme.TEXT : Theme.TEXT_DIM);
        }
        text.setTypeface(Typeface.DEFAULT);
        stroke.setColor(Theme.LINE);
        stroke.setStrokeWidth(1);
        c.drawLine(tabW + pad, pad, tabW + pad, getHeight() - pad, stroke);

        // Rows
        float left = tabW + 2 * pad;
        float right = vw - pad - 6 * u;
        List<Ui.Row> rows = ui.rows();
        int visible = (int) ((getHeight() - 2 * pad) / rowH);
        int first = Math.max(0, Math.min(ui.row() - visible / 2, rows.size() - visible));
        for (int i = first; i < rows.size() && i < first + visible; i++) {
            Ui.Row row = rows.get(i);
            float t = pad + (i - first) * rowH;
            boolean sel = i == ui.row() && ui.focus() != Ui.MenuFocus.TABS;
            boolean available = row.setting == null || row.setting.isAvailable(p);
            if (sel) {
                roundRect(c, left, t + 2 * u, right, t + rowH - 2 * u, ui.focus() == Ui.MenuFocus.EDIT ? 0x44F0A030 : 0x33FFFFFF);
            }
            textSize(Theme.TEXT_NORMAL);
            drawText(c, row.label, left + 10 * u, t + rowH - 13 * u, available ? Theme.TEXT : Theme.TEXT_DISABLED);

            String value = row.setting != null ? (available ? row.setting.currentText(p) : "nicht verfügbar") : row.info;
            if (value != null && value.length() > 0) {
                boolean editing = sel && ui.focus() == Ui.MenuFocus.EDIT;
                if (editing) {
                    value = "‹ " + value + " ›";
                }
                text.setTypeface(editing ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                float w = text.measureText(value);
                drawText(c, value, right - 10 * u - w, t + rowH - 15 * u,
                        editing ? Theme.ACCENT : available ? Theme.TEXT_DIM : Theme.TEXT_DISABLED);
                text.setTypeface(Typeface.DEFAULT);
            }
        }

        // Scroll indicator when the list is longer than the screen
        if (rows.size() > visible) {
            float trackT = pad, trackB = getHeight() - pad;
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
        outline(c, l, t, r, b, Theme.TAB_COLORS[5], 2 * u);
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
        float t = 50 * u;
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
