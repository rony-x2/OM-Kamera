package com.github.ronyx2.omkamera;

import android.graphics.Color;

/**
 * All colors and sizes in one place. The look can be replaced later without touching the logic.
 * The camera's overlay plane uses RGBA4444, so colors are kept simple and alpha values coarse.
 * Sizes are given for a 480 px high screen and scaled by {@link #unit}.
 */
public final class Theme {
    private Theme() {
    }

    public static final int TEXT = Color.WHITE;
    public static final int TEXT_DIM = 0xFFB0B4BA;
    public static final int TEXT_DISABLED = 0x80FFFFFF;
    public static final int PANEL = 0xCC101317;
    public static final int PANEL_SOLID = 0xEE101317;
    public static final int CELL = 0xAA20252B;
    public static final int CELL_DISABLED = 0x6620252B;
    public static final int ACCENT = 0xFFF0A030;
    public static final int ACCENT_TEXT = 0xFF101317;
    public static final int LINE = 0x55FFFFFF;
    public static final int WARN = 0xFFFF6A50;

    /** Color per menu tab, as orientation like in the newer camera menus */
    public static final int[] TAB_COLORS = {
        0xFFE0564E, // Aufnahme
        0xFFF0A030, // Belichtung
        0xFF4CB782, // Fokus
        0xFF4D8FE0, // Fn-Raster
        0xFF9AA0A8, // System
    };

    public static float unit(int viewHeight) {
        return viewHeight / 480f;
    }

    public static final float TEXT_SMALL = 15;
    public static final float TEXT_NORMAL = 19;
    public static final float TEXT_LARGE = 24;
    public static final float PAD = 8;
    public static final float RADIUS = 6;
}
