package com.github.ronyx2.omkamera;

import com.sony.scalar.sysutil.KeyStatus;
import com.sony.scalar.sysutil.ScalarInput;

/**
 * The mode dial. In app mode the camera reports the dial position as key events,
 * but does not change the exposure mode itself: the app has to set the scene mode.
 */
public final class ModeDial {
    public static final int FIRST = 534;
    public static final int LAST = 549;
    /** Key status code that holds the current dial position */
    private static final int POSITION = 572;

    private ModeDial() {
    }

    public static boolean isModeDial(int scanCode) {
        return scanCode >= FIRST && scanCode <= LAST;
    }

    /** Current dial position as scan code, or -1 if unknown. */
    public static int position() {
        try {
            KeyStatus k = ScalarInput.getKeyStatus(POSITION);
            if (k != null && k.valid == 1) {
                return k.status;
            }
        } catch (Throwable t) {
            Log.error("mode dial position", t);
        }
        return -1;
    }

    /** Scene mode for a dial position, or null if the app does not support that position. */
    public static String sceneMode(int scanCode) {
        switch (scanCode) {
            case 537: return "program-auto";
            case 539: return "aperture-priority";
            case 538: return "shutter-speed";
            case 540: return "manual-exposure";
            case 535: return "auto";
            case 536: return "superior-auto";
            default: return null;
        }
    }

    public static String name(int scanCode) {
        switch (scanCode) {
            case 537: return "P";
            case 539: return "A";
            case 538: return "S";
            case 540: return "M";
            case 535: return "AUTO";
            case 536: return "AUTO+";
            case 541: return "MR";
            case 542: return "Panorama";
            case 544: return "Film";
            case 545: return "SCN";
            default: return "Position " + scanCode;
        }
    }
}
