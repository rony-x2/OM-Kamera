package com.github.ronyx2.omkamera;

import java.util.ArrayList;
import java.util.List;

/**
 * One camera setting with three tasks: allowed values, current value, set a value.
 * The same setting is used in the Fn grid, in the menu and later on keys or over the network.
 *
 * Implementations may throw anything: a camera with an older platform version simply does not know
 * some methods (NoSuchMethodError). Such a setting is shown as unavailable.
 */
public abstract class Setting {
    public final String id;
    /** Name in the menu */
    public final String label;
    /** Short name in the Fn grid */
    public final String shortLabel;
    public final Category category;

    /** Menu tab of a setting, in the order of the tabs */
    public enum Category {
        SHOOTING("Aufnahme"),
        EXPOSURE("Belichtung"),
        IMAGE("Bild"),
        FOCUS("Fokus"),
        FLASH("Blitz");

        public final String title;

        Category(String title) {
            this.title = title;
        }
    }

    protected Setting(String id, String label, String shortLabel, Category category) {
        this.id = id;
        this.label = label;
        this.shortLabel = shortLabel;
        this.category = category;
    }

    protected abstract List<String> values(CameraSession.Params p) throws Throwable;

    protected abstract String get(CameraSession.Params p) throws Throwable;

    protected abstract void set(CameraSession.Params edit, String value) throws Throwable;

    /** Text for a value, e.g. "AUTO" for ISO 0. */
    public String display(String value) {
        return Labels.of(value);
    }

    /** Text for a value that may depend on other parameters (e.g. the EV step). */
    public String display(CameraSession.Params p, String value) {
        return display(value);
    }

    // ---- safe accessors used by the UI ----

    public List<String> safeValues(CameraSession.Params p) {
        if (p == null) {
            return new ArrayList<String>();
        }
        try {
            List<String> v = values(p);
            return v != null ? v : new ArrayList<String>();
        } catch (Throwable t) {
            return new ArrayList<String>();
        }
    }

    public String safeGet(CameraSession.Params p) {
        if (p == null) {
            return null;
        }
        try {
            return get(p);
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean isAvailable(CameraSession.Params p) {
        return safeGet(p) != null && safeValues(p).size() > 0;
    }

    /**
     * Whether the setting matters with the other current values, e.g. the colour temperature only
     * with the white balance "colour temperature". The menu hides settings that do not matter.
     */
    protected boolean relevant(CameraSession.Params p) throws Throwable {
        return true;
    }

    /** Available on this camera and meaningful right now */
    public boolean isShown(CameraSession.Params p) {
        if (!isAvailable(p)) {
            return false;
        }
        try {
            return relevant(p);
        } catch (Throwable t) {
            return false;
        }
    }

    public String currentText(CameraSession.Params p) {
        String v = safeGet(p);
        return v == null ? "–" : display(p, v);
    }

    /** Writes a value and reports the camera's answer. */
    public CameraSession.Result write(CameraSession session, String value) {
        CameraSession.Params edit;
        try {
            edit = session.createEdit();
            set(edit, value);
        } catch (Throwable t) {
            Log.error("set " + id + "=" + value, t);
            return new CameraSession.Result(false, 0, t.getClass().getSimpleName());
        }
        CameraSession.Result r = session.apply(edit);
        Log.info("set " + id + "=" + value + " -> " + (r.ok ? "ok" : "refused " + r.inhibition));
        if (r.ok && "drive".equals(id)) {
            Settings.rememberDrive(value);
        }
        return r;
    }

    /** The value next to the current one, or null at the end of the list. */
    public String step(CameraSession.Params p, int direction) {
        List<String> values = safeValues(p);
        if (values.isEmpty()) {
            return null;
        }
        int i = values.indexOf(safeGet(p));
        int n = i < 0 ? 0 : i + direction;
        if (n < 0 || n >= values.size()) {
            return null;
        }
        return values.get(n);
    }

    /** Integers from min to max (both included) in the given step, as strings */
    protected static List<String> range(int min, int max, int step) {
        List<String> out = new ArrayList<String>();
        for (int i = min; i <= max; i += step) {
            out.add(String.valueOf(i));
        }
        return out;
    }

    /** "+2", "0", "-1" */
    protected static String signed(String v) {
        try {
            int i = Integer.parseInt(v);
            return i > 0 ? "+" + i : String.valueOf(i);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    protected static List<String> strings(List<?> list) {
        List<String> out = new ArrayList<String>();
        if (list != null) {
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
