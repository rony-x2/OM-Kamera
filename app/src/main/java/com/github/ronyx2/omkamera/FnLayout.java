package com.github.ronyx2.omkamera;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Assignment of the 12 Fn cells. Stored as data, so the grid can be changed without code changes
 * (later also exported, imported and shared between cameras).
 */
public class FnLayout {
    /** Grid sizes to choose from (rows x columns); 2 x 6 is the camera's own Fn menu */
    public static final int[][] SIZES = {{1, 4}, {1, 6}, {2, 4}, {2, 5}, {2, 6}, {2, 7}, {3, 5}, {3, 6}};
    public static final int DEFAULT_SIZE = 4;
    /** Cells kept in storage; a smaller grid keeps the assignment of the hidden cells */
    public static final int MAX = 18;
    /** Marker for an empty cell */
    public static final String EMPTY = "";

    private static final String PREFS = "fnlayout";
    private static final String KEY = "cells";

    private final SharedPreferences prefs;
    private final String[] cells = new String[MAX];
    private int sizeIndex;

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
        sizeIndex = prefs.getInt("size", DEFAULT_SIZE);
        if (sizeIndex < 0 || sizeIndex >= SIZES.length) {
            sizeIndex = DEFAULT_SIZE;
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
        int j = i - Settings.DEFAULT_FN.length;
        String id = j < Settings.MORE_FN.length ? Settings.MORE_FN[j] : EMPTY;
        return Settings.get(id) != null ? id : EMPTY;
    }

    public int rows() {
        return SIZES[sizeIndex][0];
    }

    public int cols() {
        return SIZES[sizeIndex][1];
    }

    public int size() {
        return rows() * cols();
    }

    public int sizeIndex() {
        return sizeIndex;
    }

    public static String sizeLabel(int i) {
        return SIZES[i][0] + " × " + SIZES[i][1] + " (" + SIZES[i][0] * SIZES[i][1] + " Felder)";
    }

    public void setSizeIndex(int i) {
        sizeIndex = (i + SIZES.length) % SIZES.length;
        prefs.edit().putInt("size", sizeIndex).apply();
    }
}
