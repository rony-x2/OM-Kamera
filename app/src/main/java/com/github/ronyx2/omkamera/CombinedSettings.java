package com.github.ronyx2.omkamera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/*
 * Settings that the camera spreads over several parameters but the menu shows as one list,
 * the way the camera's own menu does it (e.g. "Serie Hi" = drive mode burst + speed high).
 */

/** Bildfolgemodus: single shot, burst with speed, self-timer and the bracket kinds. */
final class DriveSetting extends Setting {

    DriveSetting() {
        super("drive", "Bildfolgemodus", "Bildfolge", Category.SHOOTING);
    }

    @Override
    protected List<String> values(CameraSession.Params p) {
        List<?> drives = p.mod.getSupportedDriveModes();
        List<String> out = new ArrayList<String>();
        if (drives == null) {
            return out;
        }
        if (drives.contains("single")) {
            out.add("single");
        }
        if (drives.contains("burst")) {
            List<String> speeds = strings(p.mod.getSupportedBurstDriveSpeeds());
            // Fastest first like on the camera
            for (String s : new String[]{"high", "middle", "low"}) {
                if (speeds.contains(s)) {
                    out.add("burst-" + s);
                }
            }
            if (speeds.isEmpty()) {
                out.add("burst");
            }
        }
        if (drives.contains("single")) {
            List<String> timers = safe(new Source() {
                @Override
                public List<?> get(CameraSession.Params q) {
                    return q.mod.getSupportedSelfTimers();
                }
            }, p);
            for (String t : new String[]{"10", "5", "2"}) {
                if (timers.contains(t)) {
                    out.add("timer-" + t);
                }
            }
        }
        if (drives.contains("bracket")) {
            List<String> modes = strings(p.mod.getSupportedBracketModes());
            if (modes.contains("exposure")) {
                List<String> expo = safe(new Source() {
                    @Override
                    public List<?> get(CameraSession.Params q) {
                        return q.mod.getSupportedExposureBracketModes();
                    }
                }, p);
                if (expo.contains("continue")) {
                    out.add("bracket-c");
                }
                if (expo.contains("single") || expo.isEmpty()) {
                    out.add("bracket-s");
                }
            }
            if (modes.contains("white-balance")) {
                out.add("bracket-wb");
            }
            if (modes.contains("dro")) {
                out.add("bracket-dro");
            }
        }
        return out;
    }

    @Override
    protected String get(CameraSession.Params p) {
        String mode = p.mod.getDriveMode();
        if ("burst".equals(mode)) {
            String speed = null;
            try {
                speed = p.mod.getBurstDriveSpeed();
            } catch (Throwable ignored) {
            }
            return speed != null ? "burst-" + speed : "burst";
        }
        if ("bracket".equals(mode)) {
            String b = p.mod.getBracketMode();
            if ("white-balance".equals(b)) {
                return "bracket-wb";
            }
            if ("dro".equals(b)) {
                return "bracket-dro";
            }
            return "continue".equals(p.mod.getExposureBracketMode()) ? "bracket-c" : "bracket-s";
        }
        if ("single".equals(mode)) {
            int t = 0;
            try {
                t = p.mod.getSelfTimer();
            } catch (Throwable ignored) {
            }
            return t > 0 ? "timer-" + t : "single";
        }
        return mode;
    }

    @Override
    protected void set(CameraSession.Params e, String v) {
        if (v.startsWith("timer-")) {
            e.mod.setDriveMode("single");
            e.mod.setSelfTimer(Integer.parseInt(v.substring(6)));
            return;
        }
        // Every other mode runs without self-timer
        try {
            e.mod.setSelfTimer(0);
        } catch (Throwable ignored) {
        }
        if (v.startsWith("burst")) {
            e.mod.setDriveMode("burst");
            if (v.startsWith("burst-")) {
                e.mod.setBurstDriveSpeed(v.substring(6));
            }
        } else if (v.startsWith("bracket-")) {
            e.mod.setDriveMode("bracket");
            if (v.equals("bracket-wb")) {
                e.mod.setBracketMode("white-balance");
            } else if (v.equals("bracket-dro")) {
                e.mod.setBracketMode("dro");
            } else {
                e.mod.setBracketMode("exposure");
                e.mod.setExposureBracketMode(v.equals("bracket-c") ? "continue" : "single");
            }
        } else {
            e.mod.setDriveMode(v);
        }
    }

    @Override
    public String display(String v) {
        if ("single".equals(v)) return "Einzelbild";
        if ("burst-high".equals(v)) return "Serie Hi";
        if ("burst-middle".equals(v)) return "Serie Mid";
        if ("burst-low".equals(v)) return "Serie Lo";
        if ("burst".equals(v)) return "Serie";
        if (v.startsWith("timer-")) return "Timer " + v.substring(6) + " s";
        if ("bracket-c".equals(v)) return "Reihe Serie";
        if ("bracket-s".equals(v)) return "Reihe Einzel";
        if ("bracket-wb".equals(v)) return "WB-Reihe";
        if ("bracket-dro".equals(v)) return "DRO-Reihe";
        return Labels.of(v);
    }

    /** Shutter held down takes several pictures */
    static boolean isContinuous(String v) {
        return v != null && (v.startsWith("burst") || v.equals("bracket-c"));
    }

    interface Source {
        List<?> get(CameraSession.Params p) throws Throwable;
    }

    static List<String> safe(Source s, CameraSession.Params p) {
        try {
            return strings(s.get(p));
        } catch (Throwable t) {
            return new ArrayList<String>();
        }
    }
}

/** DRO / Auto HDR as one list: off, DRO auto, DRO level 1..5, HDR auto, HDR 1..6 EV. */
final class DroHdrSetting extends Setting {

    DroHdrSetting() {
        super("dro", "DRO / Auto HDR", "DRO/HDR", Category.EXPOSURE);
    }

    @Override
    protected List<String> values(CameraSession.Params p) {
        List<String> dro = strings(p.mod.getSupportedDROModes());
        List<String> out = new ArrayList<String>();
        out.add("off");
        if (dro.contains("auto")) {
            out.add("dro-auto");
        }
        if (dro.contains("on")) {
            int max = 5;
            try {
                max = p.mod.getMaxDROLevel();
            } catch (Throwable ignored) {
            }
            for (int i = 1; i <= max; i++) {
                out.add("dro-" + i);
            }
        }
        List<String> hdr = DriveSetting.safe(new DriveSetting.Source() {
            @Override
            public List<?> get(CameraSession.Params q) {
                return q.mod.getSupportedHDRModes();
            }
        }, p);
        if (hdr.contains("auto")) {
            out.add("hdr-auto");
        }
        if (hdr.contains("on")) {
            int min = 1;
            int max = 6;
            try {
                min = p.mod.getMinHDRExposureDifferenceLevel();
                max = p.mod.getMaxHDRExposureDifferenceLevel();
            } catch (Throwable ignored) {
            }
            for (int i = min; i <= max; i++) {
                out.add("hdr-" + i);
            }
        }
        return out;
    }

    @Override
    protected String get(CameraSession.Params p) {
        // getHDRMode() does not report the value on the a6000, the raw parameter does
        String hdr = p.params.get("hdr-mode");
        if (hdr == null) {
            try {
                hdr = p.mod.getHDRMode();
            } catch (Throwable ignored) {
            }
        }
        if ("auto".equals(hdr)) {
            return "hdr-auto";
        }
        if ("on".equals(hdr)) {
            String level = p.params.get("hdr-exposure-difference-level");
            return "hdr-" + (level != null ? level : String.valueOf(p.mod.getHDRExposureDifferenceLevel()));
        }
        String dro = p.mod.getDROMode();
        if ("auto".equals(dro)) {
            return "dro-auto";
        }
        if ("on".equals(dro)) {
            return "dro-" + p.mod.getDROLevel();
        }
        return "off";
    }

    @Override
    protected void set(CameraSession.Params e, String v) {
        boolean hdr = v.startsWith("hdr-");
        boolean dro = v.startsWith("dro-");
        // Only one of the two can be active
        if (!hdr) {
            try {
                e.mod.setHDRMode("off");
            } catch (Throwable ignored) {
            }
        }
        if (dro) {
            if (v.equals("dro-auto")) {
                e.mod.setDROMode("auto");
            } else {
                e.mod.setDROMode("on");
                e.mod.setDROLevel(Integer.parseInt(v.substring(4)));
            }
        } else {
            e.mod.setDROMode("off");
        }
        if (hdr) {
            if (v.equals("hdr-auto")) {
                e.mod.setHDRMode("auto");
            } else {
                e.mod.setHDRMode("on");
                e.mod.setHDRExposureDifferenceLevel(Integer.parseInt(v.substring(4)));
            }
        }
    }

    @Override
    public String display(String v) {
        if ("off".equals(v)) return "Aus";
        if ("dro-auto".equals(v)) return "DRO Auto";
        if (v.startsWith("dro-")) return "DRO Lv" + v.substring(4);
        if ("hdr-auto".equals(v)) return "HDR Auto";
        if (v.startsWith("hdr-")) {
            return String.format(Locale.US, "HDR %.1f EV", Float.parseFloat(v.substring(4))).replace('.', ',');
        }
        return Labels.of(v);
    }
}

/** Blitzmodus: flash mode and flash type (sync) as one list, like the camera menu. */
final class FlashSetting extends Setting {

    FlashSetting() {
        super("flash", "Blitzmodus", "Blitz", Category.FLASH);
    }

    @Override
    protected List<String> values(CameraSession.Params p) {
        List<String> modes = p.params.getSupportedFlashModes();
        List<String> out = new ArrayList<String>();
        if (modes == null) {
            return out;
        }
        List<String> types = DriveSetting.safe(new DriveSetting.Source() {
            @Override
            public List<?> get(CameraSession.Params q) {
                return q.mod.getSupportedFlashTypes();
            }
        }, p);
        if (modes.contains("off")) {
            out.add("off");
        }
        if (modes.contains("auto")) {
            out.add("auto");
        }
        if (modes.contains("on")) {
            if (types.isEmpty()) {
                out.add("fill");
            }
            if (types.contains("front-sync")) out.add("fill");
            if (types.contains("slow-sync")) out.add("slow");
            if (types.contains("slow-rear-sync")) out.add("rear");
            if (types.contains("rear-sync")) out.add("rear-sync");
            if (types.contains("wireless")) out.add("wireless");
        }
        return out;
    }

    @Override
    protected String get(CameraSession.Params p) {
        // Only offered while the built-in flash is up or an external flash is attached
        if (!Settings.flashReady) {
            return null;
        }
        String mode = p.params.getFlashMode();
        if (!"on".equals(mode)) {
            return mode;
        }
        String type = null;
        try {
            type = p.mod.getFlashType();
        } catch (Throwable ignored) {
        }
        if ("slow-sync".equals(type)) return "slow";
        if ("slow-rear-sync".equals(type)) return "rear";
        if ("rear-sync".equals(type)) return "rear-sync";
        if ("wireless".equals(type)) return "wireless";
        return "fill";
    }

    @Override
    protected void set(CameraSession.Params e, String v) {
        if ("off".equals(v) || "auto".equals(v)) {
            e.params.setFlashMode(v);
            if ("auto".equals(v)) {
                try {
                    e.mod.setFlashType("front-sync");
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        e.params.setFlashMode("on");
        String type = "fill".equals(v) ? "front-sync" : "slow".equals(v) ? "slow-sync"
                : "rear".equals(v) ? "slow-rear-sync" : v;
        try {
            e.mod.setFlashType(type);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String display(String v) {
        if ("off".equals(v)) return "Blitz aus";
        if ("auto".equals(v)) return "Auto";
        if ("fill".equals(v)) return "Aufhellblitz";
        if ("slow".equals(v)) return "Langzeitsync.";
        if ("rear".equals(v) || "rear-sync".equals(v)) return "2. Vorhang";
        if ("wireless".equals(v)) return "Drahtlos";
        return Labels.of(v);
    }
}
