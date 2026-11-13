package com.github.ronyx2.omkamera;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import com.github.ma1co.openmemories.framework.DeviceInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * State machine of the user interface. Every key goes to the active state first.
 * Drawing is done by {@link OverlayView}, which only reads from here.
 */
public class Ui {

    public enum State {
        /** Live view with status line */
        LIVE,
        /** Fn grid open, a cell is selected */
        FN,
        /** Fn grid, value of the selected cell is being changed */
        FN_EDIT,
        /** Menu with tabs */
        MENU
    }

    public enum MenuFocus { TABS, LIST, EDIT, PICK }

    public interface Host {
        void redraw();

        void exitApp();

        void playFocusBeep();

        /** Applies the standby setting. */
        void applyStandby();

        /** Switches WLAN and debug server according to the settings. */
        void applyNetwork();

        String wifiStatus();

        /** Address of the debug server, or null if it does not run */
        String debugAddress();
    }

    public enum FocusResult { NONE, OK, FAILED }

    /** What a dial changes in the current exposure mode */
    public enum DialRole { SHUTTER, APERTURE, EV, SHIFT, NONE }

    /** A row in the menu. */
    public static final class Row {
        public final String label;
        public final Setting setting;
        public final Runnable action;
        public final String info;
        public final int fnSlot;

        Row(String label, Setting setting, Runnable action, String info, int fnSlot) {
            this.label = label;
            this.setting = setting;
            this.action = action;
            this.info = info;
            this.fnSlot = fnSlot;
        }
    }

    public static final String[] TABS = {"Aufnahme", "Belichtung", "Fokus", "Fn-Raster", "System"};
    private static final int TAB_FN = 3;
    private static final int TAB_SYSTEM = 4;

    private final CameraSession session;
    private final FnLayout fn;
    private final Host host;
    private final SharedPreferences prefs;

    private boolean beepEnabled;
    private boolean framesEnabled;
    private boolean wifiEnabled;
    private boolean debugEnabled;
    private boolean appShutter;
    /** Standby while the app runs: -1 camera setting, 0 never, otherwise minutes without use */
    private int standbyMinutes;
    private static final int[] STANDBY_STEPS = {-1, 5, 10, 30, 0};

    // Battery as reported by the system, -1 if unknown
    private int batteryLevel = -1;
    private boolean batteryPlugged;

    // Autofocus result
    private int focusStatus;
    private int[] focusIndices = new int[0];
    private FocusResult focusResult = FocusResult.NONE;

    private State state = State.LIVE;
    private int fnIndex = 0;
    private boolean infoVisible = true;

    private int tab = 0;
    private int row = 0;
    private MenuFocus focus = MenuFocus.TABS;
    /** List shown when choosing the setting for an Fn cell */
    private List<Setting> pickList;
    private int pickIndex;

    private String message;
    private boolean messageWarn;
    private long messageUntil;

    public Ui(CameraSession session, FnLayout fn, Host host, SharedPreferences prefs) {
        this.session = session;
        this.fn = fn;
        this.host = host;
        this.prefs = prefs;
        beepEnabled = prefs.getBoolean("beep", true);
        framesEnabled = prefs.getBoolean("frames", true);
        wifiEnabled = prefs.getBoolean("wifi", false);
        debugEnabled = prefs.getBoolean("debug", false);
        appShutter = prefs.getBoolean("appshutter", true);
        standbyMinutes = prefs.getInt("standby", -1);
    }

    public int standbyMinutes() { return standbyMinutes; }

    private static String standbyText(int m) {
        return m < 0 ? "wie Kamera" : m == 0 ? "nie" : "nach " + m + " min";
    }

    /** true: the app controls AF and capture (AF frames and beep work); false: the camera does it directly */
    public boolean appShutter() { return appShutter; }

    public boolean wifiEnabled() { return wifiEnabled; }
    public boolean debugEnabled() { return debugEnabled; }

    public void setBattery(int level, boolean plugged) {
        batteryLevel = level;
        batteryPlugged = plugged;
        host.redraw();
    }

    public int batteryLevel() { return batteryLevel; }
    public boolean batteryPlugged() { return batteryPlugged; }

    // ---- autofocus ----

    public void onFocus(int status, int[] indices) {
        boolean newLock = status == 1 && focusStatus != 1;
        focusStatus = status;
        focusIndices = indices;
        if (newLock) {
            confirmFocus();
        }
        if (status == 0) {
            focusResult = FocusResult.NONE;
            // The AF area may have moved (flexible spot), read it again next time
            session.invalidateFocusFrames();
        }
        host.redraw();
    }

    /** Result of the AF run of the current half press. */
    public void onFocusResult(boolean success) {
        if (success) {
            confirmFocus();
        } else if (focusResult == FocusResult.NONE) {
            focusResult = FocusResult.FAILED;
        }
        host.redraw();
    }

    /** First successful focus of a half press: green frames and one beep. */
    private void confirmFocus() {
        if (focusResult != FocusResult.OK && beepEnabled) {
            host.playFocusBeep();
        }
        focusResult = FocusResult.OK;
    }

    /** Half press released */
    public void onFocusReleased() {
        focusResult = FocusResult.NONE;
        focusStatus = 0;
        focusIndices = new int[0];
        host.redraw();
    }

    public FocusResult focusResult() { return focusResult; }

    public int focusStatus() { return focusStatus; }
    public int[] focusIndices() { return focusIndices; }
    public boolean framesEnabled() { return framesEnabled; }

    // ---- mode dial ----

    /** Follows the mode dial. Called with the dial position as scan code. */
    public void onModeDial(int position) {
        String scene = ModeDial.sceneMode(position);
        Log.info("mode dial " + ModeDial.name(position) + " -> " + scene);
        if (scene == null) {
            showMessage("Modus " + ModeDial.name(position) + " wird in der App nicht unterstützt", true);
            return;
        }
        CameraSession.Result r = session.setSceneMode(scene);
        if (!r.ok) {
            showMessage("Modus " + ModeDial.name(position) + " nicht verfügbar", true);
        }
        session.invalidateFocusFrames();
        host.redraw();
    }

    // ---- dials ----

    /** Role of the upper/front dial */
    public DialRole dial1Role() {
        switch (session.exposureMode()) {
            case M: case S: return DialRole.SHUTTER;
            case A: return DialRole.APERTURE;
            case P: return DialRole.SHIFT;
            default: return DialRole.NONE;
        }
    }

    /** Role of the control wheel and the rear dial */
    public DialRole dial2Role() {
        switch (session.exposureMode()) {
            case M: return DialRole.APERTURE;
            case S: case A: case P: return DialRole.EV;
            default: return DialRole.NONE;
        }
    }

    private void turn(DialRole role, int dir) {
        switch (role) {
            case SHUTTER: session.adjustShutterSpeed(dir); break;
            case APERTURE: session.adjustAperture(dir); break;
            case SHIFT: session.adjustProgramShift(dir); break;
            case EV: stepSetting(Settings.get("ev"), dir); break;
            default:
                showMessage("Im Modus " + modeName() + " nicht einstellbar", true);
                break;
        }
    }

    public String modeName() {
        CameraSession.ExposureMode m = session.exposureMode();
        if (m != CameraSession.ExposureMode.OTHER) {
            return m.letter;
        }
        String scene = session.sceneMode();
        return scene != null ? scene : "?";
    }

    // ---- read access for drawing ----

    public State state() { return state; }
    public int fnIndex() { return fnIndex; }
    public boolean infoVisible() { return infoVisible; }
    public int tab() { return tab; }
    public int row() { return row; }
    public MenuFocus focus() { return focus; }
    public List<Setting> pickList() { return pickList; }
    public int pickIndex() { return pickIndex; }
    public FnLayout fnLayout() { return fn; }
    public CameraSession session() { return session; }

    public String message() {
        return message != null && SystemClock.uptimeMillis() < messageUntil ? message : null;
    }

    public boolean messageWarn() { return messageWarn; }

    public void showMessage(String text, boolean warn) {
        message = text;
        messageWarn = warn;
        messageUntil = SystemClock.uptimeMillis() + 2500;
        host.redraw();
    }

    public List<Row> rows() {
        List<Row> out = new ArrayList<Row>();
        switch (tab) {
            case 0:
                addSettings(out, Setting.Category.SHOOTING);
                break;
            case 1:
                addSettings(out, Setting.Category.EXPOSURE);
                break;
            case 2:
                addSettings(out, Setting.Category.FOCUS);
                break;
            case TAB_FN:
                for (int i = 0; i < FnLayout.SIZE; i++) {
                    Setting s = fn.get(i);
                    String pos = (i / FnLayout.COLS == 0 ? "Oben " : "Unten ") + (i % FnLayout.COLS + 1);
                    out.add(new Row(pos, null, null, s != null ? s.label : "(leer)", i));
                }
                out.add(new Row("Standardbelegung", null, new Runnable() {
                    @Override
                    public void run() {
                        fn.reset();
                        showMessage("Fn-Raster zurückgesetzt", false);
                    }
                }, "", -1));
                break;
            case TAB_SYSTEM:
                DeviceInfo d = DeviceInfo.getInstance();
                out.add(new Row("Modell", null, null, safe(d.getModel()), -1));
                out.add(new Row("Firmware", null, null, safe(d.getFirmwareVersion()), -1));
                out.add(new Row("API-Version", null, null, String.valueOf(d.getApiVersion()), -1));
                out.add(new Row("Android", null, null, Build.VERSION.RELEASE, -1));
                out.add(new Row("App-Version", null, null, BuildConfig.VERSION_NAME, -1));
                out.add(new Row("Signalton bei Fokus", null, new Runnable() {
                    @Override
                    public void run() {
                        beepEnabled = !beepEnabled;
                        prefs.edit().putBoolean("beep", beepEnabled).apply();
                    }
                }, beepEnabled ? "Ein" : "Aus", -1));
                out.add(new Row("Fokusfelder anzeigen", null, new Runnable() {
                    @Override
                    public void run() {
                        framesEnabled = !framesEnabled;
                        prefs.edit().putBoolean("frames", framesEnabled).apply();
                    }
                }, framesEnabled ? "Ein" : "Aus", -1));
                out.add(new Row("Auslöser-Steuerung", null, new Runnable() {
                    @Override
                    public void run() {
                        appShutter = !appShutter;
                        prefs.edit().putBoolean("appshutter", appShutter).apply();
                        showMessage("Wirkt nach dem nächsten Start der App", false);
                    }
                }, appShutter ? "App (mit Fokusanzeige)" : "Kamera direkt", -1));
                out.add(new Row("Standby in der App", null, new Runnable() {
                    @Override
                    public void run() {
                        int i = 0;
                        while (i < STANDBY_STEPS.length && STANDBY_STEPS[i] != standbyMinutes) {
                            i++;
                        }
                        standbyMinutes = STANDBY_STEPS[(i + 1) % STANDBY_STEPS.length];
                        prefs.edit().putInt("standby", standbyMinutes).apply();
                        host.applyStandby();
                    }
                }, standbyText(standbyMinutes), -1));
                out.add(new Row("WLAN in der App", null, new Runnable() {
                    @Override
                    public void run() {
                        wifiEnabled = !wifiEnabled;
                        if (!wifiEnabled) {
                            debugEnabled = false;
                        }
                        prefs.edit().putBoolean("wifi", wifiEnabled).putBoolean("debug", debugEnabled).apply();
                        host.applyNetwork();
                    }
                }, wifiEnabled ? "Ein" : "Aus", -1));
                out.add(new Row("WLAN-Status", null, null, host.wifiStatus(), -1));
                out.add(new Row("Debug-Server", null, new Runnable() {
                    @Override
                    public void run() {
                        debugEnabled = !debugEnabled;
                        if (debugEnabled) {
                            wifiEnabled = true;
                        }
                        prefs.edit().putBoolean("wifi", wifiEnabled).putBoolean("debug", debugEnabled).apply();
                        host.applyNetwork();
                    }
                }, debugEnabled ? (host.debugAddress() != null ? host.debugAddress() : "Ein, wartet auf WLAN") : "Aus", -1));
                out.add(new Row("App beenden", null, new Runnable() {
                    @Override
                    public void run() {
                        host.exitApp();
                    }
                }, "", -1));
                break;
            default:
                break;
        }
        return out;
    }

    private void addSettings(List<Row> out, Setting.Category c) {
        for (Setting s : Settings.of(c)) {
            out.add(new Row(s.label, s, null, null, -1));
        }
    }

    private static String safe(String s) {
        return s == null ? "–" : s;
    }

    // ---- keys ----

    /** Returns true if the key was handled, false to leave it to the camera. */
    public boolean onKey(Keys.Key key) {
        if (key == Keys.Key.SHUTTER_HALF || key == Keys.Key.SHUTTER_FULL) {
            // Half press always returns to shooting, like on the camera itself
            if (state != State.LIVE) {
                state = State.LIVE;
                host.redraw();
            }
            return false;
        }
        boolean handled;
        switch (state) {
            case LIVE: handled = onLiveKey(key); break;
            case FN: handled = onFnKey(key); break;
            case FN_EDIT: handled = onFnEditKey(key); break;
            case MENU: handled = onMenuKey(key); break;
            default: handled = false;
        }
        if (handled) {
            host.redraw();
        }
        return handled;
    }

    private boolean onLiveKey(Keys.Key key) {
        switch (key) {
            case FN:
                state = State.FN;
                return true;
            case MENU:
                openMenu();
                return true;
            case DISP:
                infoVisible = !infoVisible;
                return true;
            case DIAL1_CW: case DIAL1_CCW:
                turn(dial1Role(), Keys.direction(key));
                return true;
            case DIAL2_CW: case DIAL2_CCW: case WHEEL_CW: case WHEEL_CCW:
                turn(dial2Role(), Keys.direction(key));
                return true;
            case DIAL3_CW: case DIAL3_CCW:
                stepSetting(Settings.get("ev"), Keys.direction(key));
                return true;
            default:
                return false;
        }
    }

    private boolean onFnKey(Keys.Key key) {
        int r = fnIndex / FnLayout.COLS;
        int c = fnIndex % FnLayout.COLS;
        switch (key) {
            case LEFT: c = (c + FnLayout.COLS - 1) % FnLayout.COLS; break;
            case RIGHT: c = (c + 1) % FnLayout.COLS; break;
            case UP: case DOWN: r = 1 - r; break;
            case ENTER:
                if (fn.get(fnIndex) != null) {
                    state = State.FN_EDIT;
                }
                return true;
            case WHEEL_CW: case WHEEL_CCW: case DIAL1_CW: case DIAL1_CCW:
            case DIAL2_CW: case DIAL2_CCW: case DIAL3_CW: case DIAL3_CCW:
                // Like on the camera: a dial changes the selected cell directly
                stepSetting(fn.get(fnIndex), Keys.direction(key));
                return true;
            case FN: case MENU: case BACK:
                state = State.LIVE;
                return true;
            default:
                return false;
        }
        fnIndex = r * FnLayout.COLS + c;
        return true;
    }

    private boolean onFnEditKey(Keys.Key key) {
        int dir = Keys.direction(key);
        if (key == Keys.Key.LEFT) dir = -1;
        if (key == Keys.Key.RIGHT) dir = 1;
        if (dir != 0) {
            stepSetting(fn.get(fnIndex), dir);
            return true;
        }
        switch (key) {
            case ENTER: case FN: case BACK: case UP: case DOWN:
                state = State.FN;
                return true;
            case MENU:
                state = State.LIVE;
                return true;
            default:
                return false;
        }
    }

    private void openMenu() {
        state = State.MENU;
        focus = MenuFocus.TABS;
        row = 0;
    }

    private boolean onMenuKey(Keys.Key key) {
        if (key == Keys.Key.MENU) {
            state = State.LIVE;
            return true;
        }
        switch (focus) {
            case TABS:
                switch (key) {
                    case UP: case WHEEL_CCW: tab = (tab + TABS.length - 1) % TABS.length; row = 0; return true;
                    case DOWN: case WHEEL_CW: tab = (tab + 1) % TABS.length; row = 0; return true;
                    case RIGHT: case ENTER: focus = MenuFocus.LIST; row = 0; return true;
                    case BACK: case LEFT: state = State.LIVE; return true;
                    default: return false;
                }
            case LIST: {
                List<Row> rows = rows();
                switch (key) {
                    case UP: case WHEEL_CCW: row = (row + rows.size() - 1) % rows.size(); return true;
                    case DOWN: case WHEEL_CW: row = (row + 1) % rows.size(); return true;
                    case LEFT: case BACK: focus = MenuFocus.TABS; return true;
                    case ENTER: case RIGHT: enterRow(rows.get(row)); return true;
                    default: return false;
                }
            }
            case EDIT: {
                Setting s = rows().get(row).setting;
                int dir = Keys.direction(key);
                if (key == Keys.Key.LEFT) dir = -1;
                if (key == Keys.Key.RIGHT) dir = 1;
                if (dir != 0) {
                    stepSetting(s, dir);
                    return true;
                }
                if (key == Keys.Key.ENTER || key == Keys.Key.BACK || key == Keys.Key.UP || key == Keys.Key.DOWN) {
                    focus = MenuFocus.LIST;
                    return true;
                }
                return false;
            }
            case PICK:
                switch (key) {
                    case UP: case WHEEL_CCW: pickIndex = (pickIndex + pickList.size()) % (pickList.size() + 1); return true;
                    case DOWN: case WHEEL_CW: pickIndex = (pickIndex + 1) % (pickList.size() + 1); return true;
                    case ENTER: {
                        // Index pickList.size() is the entry "(leer)"
                        int slot = rows().get(row).fnSlot;
                        Setting chosen = pickIndex < pickList.size() ? pickList.get(pickIndex) : null;
                        fn.set(slot, chosen != null ? chosen.id : FnLayout.EMPTY);
                        focus = MenuFocus.LIST;
                        return true;
                    }
                    case BACK: case LEFT:
                        focus = MenuFocus.LIST;
                        return true;
                    default:
                        return false;
                }
            default:
                return false;
        }
    }

    private void enterRow(Row r) {
        if (r.setting != null) {
            if (r.setting.isAvailable(session.current())) {
                focus = MenuFocus.EDIT;
            } else {
                showMessage(r.setting.label + ": an dieser Kamera nicht verfügbar", true);
            }
        } else if (r.fnSlot >= 0) {
            pickList = Settings.all();
            Setting current = fn.get(r.fnSlot);
            pickIndex = current != null ? pickList.indexOf(current) : pickList.size();
            focus = MenuFocus.PICK;
        } else if (r.action != null) {
            r.action.run();
        }
    }

    /** Moves a setting one value forward or back and reports a refusal by the camera. */
    private void stepSetting(Setting s, int direction) {
        if (s == null || direction == 0) {
            return;
        }
        CameraSession.Params p = session.current();
        if (!s.isAvailable(p)) {
            showMessage(s.label + ": nicht verfügbar", true);
            return;
        }
        String next = s.step(p, direction);
        if (next == null) {
            return;
        }
        CameraSession.Result r = s.write(session, next);
        if (!r.ok) {
            showMessage(s.label + ": gesperrt" + (r.inhibition != 0 ? " (Grund " + r.inhibition + ")" : ""), true);
        }
    }
}
