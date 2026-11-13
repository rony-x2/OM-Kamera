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
    public static final int CELL = 0xDD20252B;
    public static final int CELL_DISABLED = 0x9920252B;
    public static final int ACCENT = 0xFFF0A030;
    public static final int ACCENT_TEXT = 0xFF101317;
    public static final int LINE = 0x55FFFFFF;
    public static final int WARN = 0xFFFF6A50;
    /** AF frame: idle, focused, not focused */
    public static final int AF_IDLE = 0xCCFFFFFF;
    public static final int AF_OK = 0xFF3BD16F;
    public static final int AF_WARN = 0xFFFF6A50;

    /** Color per menu tab, as orientation like in the newer camera menus */
    public static final int[] TAB_COLORS = {
        0xFFB0B8C4, // Haupt
        0xFFE0564E, // Aufnahme
        0xFFF0A030, // Belichtung/Farbe
        0xFF4CB782, // Fokus
        0xFFB37AE0, // Netzwerk
        0xFFE8CF4A, // Einstellung
        0xFF3FC1C9, // Mein Menü
    };

    /** Colour of the Fn grid and network markers */
    public static final int FN_BLUE = 0xFF4D8FE0;
    /** Histogram and level */
    public static final int GUIDE = 0x99FFFFFF;
    public static final int HIST_R = 0xCCFF5A5A;
    public static final int HIST_G = 0xCC5AE07A;
    public static final int HIST_B = 0xCC5A9AFF;

    public static float unit(int viewHeight) {
        return viewHeight / 480f;
    }

    public static final float TEXT_SMALL = 18;
    public static final float TEXT_NORMAL = 22;
    public static final float TEXT_LARGE = 28;
    /** Height of the exposure bar at the bottom */
    public static final float BAR = 54;
    /** Height of an Fn cell */
    public static final float CELL_H = 74;
    /** Size of icons in the Fn grid and in the status line */
    public static final float ICON = 38;
    public static final float PAD = 8;
    public static final float RADIUS = 6;
}
