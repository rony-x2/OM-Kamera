package com.github.ronyx2.omkamera;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Assignment of the 12 Fn cells. Stored as data, so the grid can be changed without code changes
 * (later also exported, imported and shared between cameras).
 */
public class FnLayout {
    public static final int ROWS = 2;
    public static final int COLS = 6;
    public static final int SIZE = ROWS * COLS;
    /** Marker for an empty cell */
    public static final String EMPTY = "";

    private static final String PREFS = "fnlayout";
    private static final String KEY = "cells";

    private final SharedPreferences prefs;
    private final String[] cells = new String[SIZE];

    public FnLayout(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        String stored = prefs.getString(KEY, null);
        String[] parts = stored != null ? stored.split(",", -1) : null;
        for (int i = 0; i < SIZE; i++) {
            String id = parts != null && i < parts.length ? parts[i] : Settings.DEFAULT_FN[i];
            cells[i] = EMPTY.equals(id) || Settings.get(id) != null ? id : EMPTY;
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SIZE; i++) {
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
        for (int i = 0; i < SIZE; i++) {
            cells[i] = Settings.DEFAULT_FN[i];
        }
        save();
    }
}
