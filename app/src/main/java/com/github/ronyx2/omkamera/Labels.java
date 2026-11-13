package com.github.ronyx2.omkamera;

import java.util.HashMap;
import java.util.Map;

/** German display texts for the camera's internal value names. Unknown values are shown as they are. */
public final class Labels {
    private static final Map<String, String> MAP = new HashMap<String, String>();

    static {
        // White balance
        put("auto", "Auto");
        put("daylight", "Tageslicht");
        put("cloudy-daylight", "Wolkig");
        put("shade", "Schatten");
        put("incandescent", "Glühlampe");
        put("fluorescent", "Leuchtst.");
        put("fluorescent-coolwhite", "Leuchtst. kalt");
        put("fluorescent-daywhite", "Leuchtst. neutral");
        put("fluorescent-daylight", "Leuchtst. Tag");
        put("warm-fluorescent", "Leuchtst. warm");
        put("flash", "Blitz");
        put("twilight", "Dämmerung");
        put("color-temperature", "Farbtemp.");
        put("custom", "Benutzer");
        put("custom1", "Benutzer 1");
        put("custom2", "Benutzer 2");
        put("custom3", "Benutzer 3");
        put("underwater-auto", "Unterwasser");
        // Focus
        put("manual", "MF");
        put("af-s", "AF-S");
        put("af-c", "AF-C");
        put("af-a", "AF-A");
        put("dmf", "DMF");
        put("wide", "Breit");
        put("semi-multi", "Feld");
        put("semiwide", "Feld");
        put("center", "Mitte");
        put("flex-spot", "Flex. Spot");
        put("local", "Flex. Spot");
        // Metering
        put("multi", "Multi");
        put("spot", "Spot");
        // DRO
        put("off", "Aus");
        put("on", "Ein");
        put("dro", "DRO");
        put("hdr", "Auto-HDR");
        // Creative style
        put("standard", "Standard");
        put("vivid", "Lebhaft");
        put("neutral", "Neutral");
        put("clear", "Klar");
        put("deep", "Tief");
        put("light", "Hell");
        put("portrait", "Porträt");
        put("landscape", "Landschaft");
        put("sunset", "Sonnenunterg.");
        put("night", "Nachtszene");
        put("red-leaves", "Herbstlaub");
        put("mono", "Schwarzweiß");
        put("sepia", "Sepia");
        // Drive mode
        put("single", "Einzelbild");
        put("burst", "Serie");
        put("selftimer", "Selbstausl.");
        put("bracket", "Reihe");
        put("high", "Hoch");
        put("middle", "Mittel");
        put("midium", "Mittel");
        put("low", "Niedrig");
        // Flash ("auto", "on", "off" are shared with other settings)
        put("red-eye", "Rote Augen");
        put("slow-sync", "Langzeitsync.");
        put("rear-sync", "2. Verschlussv.");
        put("slow-rear-sync", "Langz. 2. V.");
        put("fill-in", "Aufhellblitz");
        put("wireless", "Drahtlos");
        // Quality, size
        put("raw", "RAW");
        put("rawjpeg", "RAW+JPEG");
        put("jpeg", "JPEG");
        put("jpegextrafine", "Extrafein");
        put("jpegfine", "Fein");
        put("jpegstandard", "Standard");
        put("large", "L");
        put("middle-size", "M");
        put("small", "S");
    }

    private static void put(String k, String v) {
        MAP.put(k, v);
    }

    private Labels() {
    }

    public static String of(String value) {
        if (value == null) {
            return "–";
        }
        String s = MAP.get(value);
        return s != null ? s : value;
    }
}
