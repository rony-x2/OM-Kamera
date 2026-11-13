package com.github.ronyx2.omkamera;

import android.content.SharedPreferences;

/**
 * Custom keys like the camera's "BenKey/ReglEinst": which function a key has in the live view.
 * Stored in the app's preferences as "key.&lt;KEY&gt;" = function name.
 */
public final class KeyFunctions {

    public enum Function {
        NONE("Keine Funktion", null),
        AEL_HOLD("AEL halten", null),
        AEL_TOGGLE("AEL umschalten", null),
        MAGNIFY("Fokuslupe", null),
        AF_MOVE("Fokusfeld verschieben", null),
        AF_CENTER("Fokusfeld zur Mitte", null),
        AF_MF("AF/MF umschalten", null),
        FN_MENU("Fn-Raster", null),
        MY_MENU("Mein Menü", null),
        GRID("Gitterlinien ein/aus", null),
        HISTO("Histogramm ein/aus", null),
        DRIVE("Bildfolgemodus", "drive"),
        ISO("ISO", "iso"),
        EV("Belichtungskorrektur", "ev"),
        WB("Weißabgleich", "wb"),
        METERING("Messmethode", "metering"),
        DRO("DRO / Auto HDR", "dro"),
        STYLE("Kreativmodus", "style"),
        AF_MODE("AF-Modus", "afmode"),
        AF_AREA("Fokusfeld", "afarea"),
        QUALITY("Qualität", "quality"),
        FLASH("Blitzmodus", "flash");

        public final String label;
        /** For functions that open a quick change of one setting */
        public final String setting;

        Function(String label, String setting) {
            this.label = label;
            this.setting = setting;
        }
    }

    /** Keys that can get a function, with their names in the menu and the defaults */
    public static final Keys.Key[] KEYS = {
        Keys.Key.C1, Keys.Key.C2, Keys.Key.DELETE, Keys.Key.AEL, Keys.Key.ENTER,
        Keys.Key.LEFT, Keys.Key.RIGHT, Keys.Key.DOWN, Keys.Key.LENS_HOLD
    };

    public static String keyName(Keys.Key k) {
        switch (k) {
            case C1: return "Taste C1";
            case C2: return "Taste C2";
            case DELETE: return "Löschtaste";
            case AEL: return "Taste AEL";
            case ENTER: return "Mitteltaste";
            case LEFT: return "Rad links";
            case RIGHT: return "Rad rechts";
            case DOWN: return "Rad unten";
            case LENS_HOLD: return "Fokus-Haltetaste (Objektiv)";
            default: return k.name();
        }
    }

    private static Function defaultFor(Keys.Key k) {
        switch (k) {
            case C1: return Function.MAGNIFY;
            case C2: return Function.AF_CENTER;
            case DELETE: return Function.AF_CENTER;
            case AEL: return Function.AEL_HOLD;
            case ENTER: return Function.AF_MOVE;
            case LEFT: return Function.DRIVE;
            case RIGHT: return Function.ISO;
            case DOWN: return Function.EV;
            case LENS_HOLD: return Function.AEL_HOLD;
            default: return Function.NONE;
        }
    }

    private final SharedPreferences prefs;

    public KeyFunctions(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public static boolean assignable(Keys.Key k) {
        for (Keys.Key x : KEYS) {
            if (x == k) {
                return true;
            }
        }
        return false;
    }

    public Function get(Keys.Key k) {
        String v = prefs.getString("key." + k.name(), null);
        if (v != null) {
            try {
                return Function.valueOf(v);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return defaultFor(k);
    }

    /** Next function in the list, for the menu */
    public void cycle(Keys.Key k, int dir) {
        Function[] all = Function.values();
        int i = (get(k).ordinal() + dir + all.length) % all.length;
        prefs.edit().putString("key." + k.name(), all[i].name()).apply();
    }

    public void reset() {
        SharedPreferences.Editor e = prefs.edit();
        for (Keys.Key k : KEYS) {
            e.remove("key." + k.name());
        }
        e.apply();
    }
}
