package com.github.ronyx2.omkamera;

import java.util.ArrayList;
import java.util.List;

/**
 * Looks modelled on the "Kreativer Look" of newer Sony cameras. The ILCE-6000 has no such
 * presets, so each look is put together from what it has: creative style, contrast, saturation,
 * sharpness and the white balance shift (amber–blue, green–magenta), all written in one edit.
 * The values are an approximation of the look's character, not a copy of Sony's processing.
 */
public class LookSetting extends Setting {

    private static final class Look {
        final String id, name, style;
        final int contrast, saturation, sharpness, ab, gm;

        Look(String id, String name, String style, int contrast, int saturation, int sharpness, int ab, int gm) {
            this.id = id;
            this.name = name;
            this.style = style;
            this.contrast = contrast;
            this.saturation = saturation;
            this.sharpness = sharpness;
            this.ab = ab;
            this.gm = gm;
        }
    }

    // ab > 0 amber, < 0 blue; gm > 0 magenta, < 0 green
    private static final Look[] LOOKS = {
        new Look("ST", "ST Standard", "standard", 0, 0, 0, 0, 0),
        new Look("PT", "PT Porträt, weiche Haut", "portrait", -1, -1, -1, 1, 1),
        new Look("NT", "NT Neutral, zum Bearbeiten", "neutral", 0, -1, -2, 0, 0),
        new Look("VV", "VV Lebhaft", "vivid", 0, 0, 0, 0, 0),
        new Look("VV2", "VV2 Kräftig und klar", "vivid", 1, 1, 1, 0, 0),
        new Look("FL", "FL Film, ruhig und kühl", "standard", 1, -2, 0, -1, -1),
        new Look("IN", "IN Matt, gedämpft", "standard", -3, -2, -1, 1, 0),
        new Look("SH", "SH Hell und luftig", "light", -2, -1, -1, -1, 0),
        new Look("BW", "BW Schwarzweiß", "mono", 0, 0, 0, 0, 0),
        new Look("SE", "SE Sepia", "sepia", 0, 0, 0, 0, 0),
    };

    public LookSetting() {
        super("look", "Kreativer Look", "Look", Category.IMAGE);
    }

    private static Look find(String id) {
        for (Look l : LOOKS) {
            if (l.id.equals(id)) {
                return l;
            }
        }
        return null;
    }

    private static boolean mono(String style) {
        return "mono".equals(style) || "sepia".equals(style);
    }

    @Override
    protected List<String> values(CameraSession.Params p) throws Throwable {
        List<String> styles = strings(p.mod.getSupportedColorModes());
        List<String> out = new ArrayList<String>();
        for (Look l : LOOKS) {
            if (styles.contains(l.style)) {
                out.add(l.id);
            }
        }
        return out;
    }

    @Override
    protected String get(CameraSession.Params p) throws Throwable {
        String style = p.mod.getColorMode();
        int c = p.mod.getContrast(), s = p.mod.getSaturation(), sh = p.mod.getSharpness();
        int ab = p.mod.getLightBalanceForWhiteBalance(), gm = p.mod.getColorCompensationForWhiteBalance();
        for (Look l : LOOKS) {
            if (l.style.equals(style) && l.contrast == c && l.sharpness == sh && l.ab == ab && l.gm == gm
                    && (mono(style) || l.saturation == s)) {
                return l.id;
            }
        }
        return "custom";
    }

    @Override
    protected void set(CameraSession.Params e, String v) throws Throwable {
        Look l = find(v);
        if (l == null) {
            throw new IllegalArgumentException("unknown look " + v);
        }
        e.mod.setColorMode(l.style);
        e.mod.setContrast(l.contrast);
        if (!mono(l.style)) {
            e.mod.setSaturation(l.saturation);
        }
        e.mod.setSharpness(l.sharpness);
        e.mod.setLightBalanceForWhiteBalance(l.ab);
        e.mod.setColorCompensationForWhiteBalance(l.gm);
    }

    @Override
    public String display(String v) {
        if ("custom".equals(v)) {
            return "Eigene";
        }
        return v;
    }

    /** Long name for lists, e.g. "FL Film, ruhig und kühl" */
    public static String longName(String v) {
        Look l = find(v);
        return l != null ? l.name : "Eigene Einstellung";
    }
}
