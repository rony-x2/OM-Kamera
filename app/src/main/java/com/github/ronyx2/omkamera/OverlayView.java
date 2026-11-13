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

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

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
        CameraSession.Params p = ui.session().current();
        switch (ui.state()) {
            case LIVE:
                if (ui.infoVisible()) {
                    drawStatusChips(c, p);
                    drawExposureBar(c, p);
                }
                break;
            case FN:
            case FN_EDIT:
                drawExposureBar(c, p);
                drawFnGrid(c, p);
                break;
            case MENU:
                drawMenu(c, p);
                break;
            default:
                break;
        }
        drawMessage(c);
    }

    // ---- live view ----

    private static final String[] CHIP_SETTINGS = {"drive", "afmf", "afmode", "afarea", "wb", "quality"};

    private void drawStatusChips(Canvas c, CameraSession.Params p) {
        float x = Theme.PAD * u;
        float y = Theme.PAD * u;
        textSize(Theme.TEXT_SMALL);
        for (String id : CHIP_SETTINGS) {
            Setting s = Settings.get(id);
            if (s == null || !s.isAvailable(p)) {
                continue;
            }
            String t = s.currentText(p);
            float w = text.measureText(t) + 2 * Theme.PAD * u;
            float h = (Theme.TEXT_SMALL + 10) * u;
            roundRect(c, x, y, x + w, y + h, Theme.PANEL);
            drawText(c, t, x + Theme.PAD * u, y + h - 8 * u, Theme.TEXT);
            x += w + 6 * u;
        }
    }

    private void drawExposureBar(Canvas c, CameraSession.Params p) {
        float h = 44 * u;
        float top = getHeight() - h;
        fill.setColor(Theme.PANEL);
        c.drawRect(0, top, getWidth(), getHeight(), fill);

        String[] items = {
            ui.session().shutterText(),
            ui.session().apertureText(),
            valueOf("ev", p),
            "ISO " + valueOf("iso", p),
        };
        textSize(Theme.TEXT_LARGE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        float slot = getWidth() / (float) items.length;
        for (int i = 0; i < items.length; i++) {
            float tw = text.measureText(items[i]);
            drawText(c, items[i], slot * i + (slot - tw) / 2, getHeight() - 13 * u, Theme.TEXT);
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
        float cellH = 64 * u;
        float bottom = getHeight() - 44 * u - pad;
        float top = bottom - FnLayout.ROWS * cellH - (FnLayout.ROWS - 1) * gap;
        float cellW = (getWidth() - 2 * pad - (FnLayout.COLS - 1) * gap) / FnLayout.COLS;

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
            drawFitted(c, s.shortLabel, l + 6 * u, t + 20 * u, cellW - 12 * u, labelColor);
            textSize(Theme.TEXT_NORMAL);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            drawFitted(c, available ? s.currentText(p) : "–", l + 6 * u, t + cellH - 14 * u, cellW - 12 * u, valueColor);
            text.setTypeface(Typeface.DEFAULT);
        }

        // Value strip above the grid while editing
        if (ui.state() == Ui.State.FN_EDIT) {
            Setting s = ui.fnLayout().get(ui.fnIndex());
            if (s != null) {
                drawValueStrip(c, s, p, pad, top - 52 * u - gap, getWidth() - pad, top - gap);
            }
        }
    }

    private void drawValueStrip(Canvas c, Setting s, CameraSession.Params p, float l, float t, float r, float b) {
        roundRect(c, l, t, r, b, Theme.PANEL_SOLID);
        List<String> values = s.safeValues(p);
        String current = s.safeGet(p);
        int i = values.indexOf(current);
        textSize(Theme.TEXT_SMALL);
        drawText(c, s.label, l + 10 * u, t + 20 * u, Theme.TEXT_DIM);
        String prev = i > 0 ? "‹ " + s.display(p, values.get(i - 1)) : "";
        String next = i >= 0 && i < values.size() - 1 ? s.display(p, values.get(i + 1)) + " ›" : "";
        float mid = (l + r) / 2;
        textSize(Theme.TEXT_NORMAL);
        drawText(c, prev, mid - 130 * u - text.measureText(prev), b - 12 * u, Theme.TEXT_DIM);
        drawText(c, next, mid + 130 * u, b - 12 * u, Theme.TEXT_DIM);
        textSize(Theme.TEXT_LARGE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        String cur = s.currentText(p);
        drawText(c, cur, mid - text.measureText(cur) / 2, b - 12 * u, Theme.ACCENT);
        text.setTypeface(Typeface.DEFAULT);
    }

    // ---- menu ----

    private void drawMenu(Canvas c, CameraSession.Params p) {
        fill.setColor(Theme.PANEL_SOLID);
        c.drawRect(0, 0, getWidth(), getHeight(), fill);

        float tabW = 150 * u;
        float rowH = 40 * u;
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
        float right = getWidth() - pad;
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
                drawText(c, value, right - 10 * u - w, t + rowH - 13 * u,
                        editing ? Theme.ACCENT : available ? Theme.TEXT_DIM : Theme.TEXT_DISABLED);
                text.setTypeface(Typeface.DEFAULT);
            }
        }

        if (ui.focus() == Ui.MenuFocus.PICK) {
            drawPickList(c, left + 40 * u, pad + 10 * u, right - 10 * u, getHeight() - pad - 10 * u, rowH);
        }
    }

    private void drawPickList(Canvas c, float l, float t, float r, float b, float rowH) {
        roundRect(c, l, t, r, b, 0xF0181C21);
        outline(c, l, t, r, b, Theme.TAB_COLORS[3], 2 * u);
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
        float l = (getWidth() - w) / 2;
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
