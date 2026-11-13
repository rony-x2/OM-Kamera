package com.github.ronyx2.omkamera;

import android.view.KeyEvent;

/**
 * Maps the camera's scan codes (see com.sony.scalar.sysutil.ScalarInput) to logical keys.
 */
public final class Keys {
    public enum Key {
        UP, DOWN, LEFT, RIGHT, ENTER, MENU, FN, DISP, PLAY, DELETE, BACK,
        C1, C2, C3,
        /** Control wheel on the back (a6000, a7II) */
        WHEEL_CW, WHEEL_CCW,
        /** Upper / front dial */
        DIAL1_CW, DIAL1_CCW,
        /** Rear dial (a7II, a7RII) */
        DIAL2_CW, DIAL2_CCW,
        /** Exposure compensation dial or third dial */
        DIAL3_CW, DIAL3_CCW,
        SHUTTER_HALF, SHUTTER_FULL,
        OTHER
    }

    private Keys() {
    }

    public static Key map(KeyEvent event) {
        switch (event.getScanCode()) {
            case 103: return Key.UP;
            case 108: return Key.DOWN;
            case 105: return Key.LEFT;
            case 106: return Key.RIGHT;
            case 232: return Key.ENTER;
            case 514: return Key.MENU;
            case 520: return Key.FN;
            case 608: return Key.DISP;
            case 207: return Key.PLAY;
            case 595: return Key.DELETE;
            case 622: return Key.C1;
            case 623: return Key.C2;
            case 588: return Key.C3;
            case 522: return Key.WHEEL_CW;
            case 523: return Key.WHEEL_CCW;
            case 525: return Key.DIAL1_CW;
            case 526: return Key.DIAL1_CCW;
            case 528: return Key.DIAL2_CW;
            case 529: return Key.DIAL2_CCW;
            case 635: return Key.DIAL3_CW;
            case 634: return Key.DIAL3_CCW;
            case 516:
            case 517: return Key.SHUTTER_HALF;
            case 518: return Key.SHUTTER_FULL;
            default:
                break;
        }
        // Fallback for the emulator and for keys that arrive as normal Android key codes
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: return Key.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Key.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Key.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Key.RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: return Key.ENTER;
            case KeyEvent.KEYCODE_MENU: return Key.MENU;
            case KeyEvent.KEYCODE_BACK: return Key.BACK;
            default: return Key.OTHER;
        }
    }

    /** +1 for clockwise, -1 for counterclockwise, 0 for other keys. */
    public static int direction(Key key) {
        switch (key) {
            case WHEEL_CW: case DIAL1_CW: case DIAL2_CW: case DIAL3_CW: return 1;
            case WHEEL_CCW: case DIAL1_CCW: case DIAL2_CCW: case DIAL3_CCW: return -1;
            default: return 0;
        }
    }
}
