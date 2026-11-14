package com.github.ronyx2.omkamera;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Assignment of the 12 Fn cells. Stored as data, so the grid can be changed without code changes
 * (later also exported, imported and shared between cameras).
 */
public class FnLayout {
    /** Slots; empty ones are not shown, the grid arranges itself (up to 3 rows of 6) */
    public static final int MAX = 18;
    /** Marker for an empty cell */
    public static final String EMPTY = "";

    private static final String PREFS = "fnlayout";
    private static final String KEY = "cells";

    private final SharedPreferences prefs;
    private final String[] cells = new String[MAX];


    public FnLayout(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        String stored = prefs.getString(KEY, null);
        String[] parts = stored != null ? stored.split(",", -1) : null;
        for (int i = 0; i < MAX; i++) {
            String id = parts != null && i < parts.length ? parts[i] : defaultCell(i);
            cells[i] = EMPTY.equals(id) || Settings.get(id) != null ? id : EMPTY;
        }

    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cells[i]);
        }
        prefs.edit().putString(KEY, sb.toString()).apply();
    }

    /** The setting in a cell, or null for an empty cell. */
    public Setting get(int index) {
        return Settings.get(cells[index]);
    }

    public void set(int index, String settingId) {
        cells[index] = settingId == null ? EMPTY : settingId;
        save();
    }

    public void reset() {
        for (int i = 0; i < MAX; i++) {
            cells[i] = defaultCell(i);
        }
        save();
    }

    private static String defaultCell(int i) {
        if (i < Settings.DEFAULT_FN.length) {
            return Settings.DEFAULT_FN[i];
        }
        return EMPTY;
    }

    /** Slots that hold a function, in their order: only these are shown */
    public int[] visible() {
        int n = 0;
        for (int i = 0; i < MAX; i++) {
            if (get(i) != null) {
                n++;
            }
        }
        int[] r = new int[n];
        n = 0;
        for (int i = 0; i < MAX; i++) {
            if (get(i) != null) {
                r[n++] = i;
            }
        }
        return r;
    }

    /** The function at a position of the shown grid, or null */
    public Setting visibleSetting(int pos) {
        int[] v = visible();
        return pos >= 0 && pos < v.length ? get(v[pos]) : null;
    }

    /** Rows of the shown grid: up to 6 cells per row, like the camera's Fn menu */
    public int rows() {
        int n = visible().length;
        return n <= 6 ? 1 : n <= 12 ? 2 : 3;
    }

    /** Cells per row, spread evenly (7 functions: 4 + 3) */
    public int cols() {
        int n = Math.max(1, visible().length);
        return (n + rows() - 1) / rows();
    }

    public int size() {
        return visible().length;
    }

}
