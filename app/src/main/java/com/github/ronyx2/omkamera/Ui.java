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
        MENU,
        /** Flexible spot is being moved with the arrow keys */
        AF_MOVE,
        /** One setting is changed directly from the live view (custom key) */
        QUICK,
        /** Focus magnifier */
        MAGNIFY
    }

    public enum MenuFocus { TABS, LIST, EDIT, PICK }

    public interface Host {
        void redraw();

        void exitApp();

        void playFocusBeep(Beep.Mode mode);

        /** Applies the standby setting. */
        void applyStandby();

        /** Switches WLAN and debug server according to the settings. */
        void applyNetwork();

        String wifiStatus();

        /** WLAN has an address */
        boolean wifiConnected();

        /** Camera clock with NTP setting */
        CameraClock clock();

        /** Starts or stops histogram and level sensor according to the settings */
        void applyDisplay();

        /** SSID, standard, speed, signal, IP of the WLAN connection, or null */
        String[] wifiDetails();

        /** Saved settings (memories 1-3, file on the card) */
        Memories memories();

        void later(Runnable r, long delayMs);

        void cancel(Runnable r);

        /** Address of the debug server, or null if it does not run */
        String debugAddress();
    }

    public enum FocusResult { NONE, OK, FAILED }

    /** What a dial changes in the current exposure mode */
    public enum DialRole { SHUTTER, APERTURE, EV, SHIFT, NONE }

    /** Chooses a setting for a slot (Fn grid, My Menu) */
    public interface Picker {
        Setting current();

        void picked(Setting s);
    }

    /** Opens a sub list, like the camera's sub pages (custom keys, Fn grid, memories) */
    public interface Sub {
        String title();

        List<Row> rows();
    }

    /** A row in the menu: a group header, a setting, an action, an info line, a picker or a sub list. */
    public static final class Row {
        public final String label;
        public final Setting setting;
        public final Runnable action;
        public final String info;
        public final Picker picker;
        public final Sub sub;
        public final boolean header;

        private Row(String label, Setting setting, Runnable action, String info, Picker picker, Sub sub, boolean header) {
            this.label = label;
            this.setting = setting;
            this.action = action;
            this.info = info;
            this.picker = picker;
            this.sub = sub;
            this.header = header;
        }

        static Row header(String title) {
            return new Row(title, null, null, null, null, null, true);
        }

        static Row of(Setting s) {
            return new Row(s.label, s, null, null, null, null, false);
        }

        static Row action(String label, String info, Runnable action) {
            return new Row(label, null, action, info, null, null, false);
        }

        static Row info(String label, String value) {
            return new Row(label, null, null, value, null, null, false);
        }

        static Row pick(String label, String info, Picker picker) {
            return new Row(label, null, null, info, picker, null, false);
        }

        static Row sub(String label, String info, Sub sub) {
            return new Row(label, null, null, info, null, sub, false);
        }
    }

    /** Tabs like the camera's menu (a7 IV and newer): main list, then by topic */
    public static final String[] TABS = {"Haupt", "Aufnahme", "Belichtung/Farbe", "Fokus", "Netzwerk", "Einstellung", "Mein Menü"};
    private static final int TAB_MAIN = 0;
    private static final int TAB_SHOOTING = 1;
    private static final int TAB_EXPOSURE = 2;
    private static final int TAB_FOCUS = 3;
    private static final int TAB_NETWORK = 4;
    private static final int TAB_SETUP = 5;
    private static final int TAB_MYMENU = 6;

    /** Grid lines like the camera: off, rule of thirds 3x3, square grid 6x4, diagonal + square */
    public enum Grid {
        OFF("Aus"), THIRDS("3×3 Raster"), GOLDEN("Goldener Schnitt"), SQUARE("Quadratraster"), DIAGONAL("Diagonal + Quadrat");
        public final String label;

        Grid(String label) {
            this.label = label;
        }
    }

    public enum Histo {
        OFF("Aus"), LUMA("Helligkeit"), RGB("RGB");
        public final String label;

        Histo(String label) {
            this.label = label;
        }
    }

    /** Aspect frame markers for cropping later */
    public enum Frame {
        OFF("Aus", 0f), R1_1("1:1", 1f), R4_5("4:5", 0.8f), R4_3("4:3", 4f / 3), R16_9("16:9", 16f / 9), R239("2,39:1", 2.39f);
        public final String label;
        public final float ratio;

        Frame(String label, float ratio) {
            this.label = label;
            this.ratio = ratio;
        }
    }

    /** Zebra levels in IRE, 0 = off, 101 = only clipped highlights ("100+") */
    public static final int[] ZEBRA_IRE = {0, 70, 75, 80, 85, 90, 95, 100, 101};
    public static final String[] PEAK_LABELS = {"Aus", "Niedrig", "Mittel", "Hoch"};
    /** Edge strength needed per sensitivity level: higher sensitivity, lower threshold */
    private static final int[] PEAK_THRESHOLDS = {0, 120, 85, 60};
    public static final String[] PEAK_COLOR_LABELS = {"Rot", "Gelb", "Weiß", "Blau"};
    private static final int[] PEAK_COLORS = {0xFFFF3B30, 0xFFFFD60A, 0xFFFFFFFF, 0xFF4DA3FF};
    public static final int[] SAFE_ZONES = {0, 90, 80};

    private final CameraSession session;
    private final FnLayout fn;
    private final Host host;
    private final SharedPreferences prefs;

    private Beep.Mode beepMode;
    private boolean writing;
    private boolean framesEnabled;
    private boolean wifiEnabled;
    private boolean debugEnabled;
    private boolean appShutter;
    private boolean remoteEnabled;
    private String remoteCode;
    private boolean mcpEnabled;
    private Grid grid;
    private Histo histo;
    private boolean levelEnabled;
    private boolean warningsEnabled;
    private boolean shotsEnabled;
    private int zebraIdx;
    private int peakIdx;
    private int peakColorIdx;
    private boolean centerMark;
    private Frame frame;
    private int safeIdx;
    /** Tilt from the acceleration sensor in degrees; NaN while unknown */
    private volatile float roll = Float.NaN;
    private volatile float pitch = Float.NaN;
    private String mcpToken;
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
    /** Exposure held with the AEL key */
    private boolean aeLocked;
    private int fnIndex = 0;
    private boolean infoVisible = true;

    private int tab = 0;
    private int row = 0;
    private MenuFocus focus = MenuFocus.TABS;
    /** List shown when choosing the setting for an Fn cell */
    private List<Setting> pickList;
    private int pickIndex;
    private Picker picker;
    /** Open sub list, null on the tab level */
    private Sub sub;
    private int subParentRow;
    private final KeyFunctions keys;
    private Sequencer sequencer;

    public void setSequencer(Sequencer s) { sequencer = s; }

    private Processor processor;
    private AutoIso autoIso;

    public void setAutoIso(AutoIso a) { autoIso = a; }

    public AutoIso autoIso() { return autoIso; }

    public void setProcessor(Processor p) { processor = p; }

    public Processor processor() { return processor; }

    /** Format for the automatic conversion of each new ARW, null = off */
    public Processor.Kind autoExport() {
        int i = prefs.getInt("autoexport", 0);
        return i == 1 ? Processor.Kind.DNG : i == 2 ? Processor.Kind.TIFF : i == 3 ? Processor.Kind.PNG : null;
    }

    private static final int[] COMBINE_COUNTS = {2, 3, 4, 5, 6, 8, 10, 15, 20, 30};

    public int combineCount() { return prefs.getInt("combinecount", 4); }

    /** PNG bits per channel: 16 (full range) or 8 (smaller, faster) */
    public int pngBits() { return prefs.getInt("pngbits", 16) == 8 ? 8 : 16; }

    public void setPngBits(int bits) { prefs.edit().putInt("pngbits", bits == 8 ? 8 : 16).apply(); }

    // ---- HEIC/AVIF (proof of concept) ----

    public static final int[] HEIF_QUALITIES = {50, 60, 70, 80, 90};
    public static final String[] HEIF_SIZES = {"24 MP (voll)", "12 MP", "6 MP"};
    private static final double[] HEIF_SCALES = {1.0, 0.7071, 0.5};

    public int heifQuality() { return prefs.getInt("heif.q", 80); }

    public int heifSizeIndex() { return Math.max(0, Math.min(2, prefs.getInt("heif.size", 0))); }

    public double heifScale() { return HEIF_SCALES[heifSizeIndex()]; }

    public int avifThreads() { return prefs.getInt("heif.avifthreads", 2) >= 4 ? 4 : 2; }

    public void setHeif(int quality, int sizeIndex, int avifThreads) {
        SharedPreferences.Editor e = prefs.edit();
        if (quality > 0) {
            e.putInt("heif.q", quality);
        }
        if (sizeIndex >= 0) {
            e.putInt("heif.size", Math.min(2, sizeIndex));
        }
        if (avifThreads > 0) {
            e.putInt("heif.avifthreads", avifThreads >= 4 ? 4 : 2);
        }
        e.apply();
    }

    public Sequencer sequencer() { return sequencer; }
    /** Setting changed by a custom key (state QUICK) */
    private Setting quick;
    /** Key that opened the quick change, it also closes it */
    private Keys.Key quickKey;

    private String message;
    private boolean messageWarn;
    private long messageUntil;

    public Ui(CameraSession session, FnLayout fn, Host host, SharedPreferences prefs) {
        this.session = session;
        this.fn = fn;
        this.host = host;
        this.prefs = prefs;
        this.keys = new KeyFunctions(prefs);
        beepMode = Beep.Mode.values()[Math.max(0, Math.min(2, prefs.getInt("beepmode", 1)))];
        framesEnabled = prefs.getBoolean("frames", true);
        wifiEnabled = prefs.getBoolean("wifi", false);
        debugEnabled = prefs.getBoolean("debug", false);
        appShutter = prefs.getBoolean("appshutter", true);
        remoteEnabled = prefs.getBoolean("remote", false);
        remoteCode = prefs.getString("remotecode", null);
        mcpEnabled = prefs.getBoolean("mcp", false);
        // The grid list got "Goldener Schnitt" at position 2 in 0.6.0
        int g = prefs.getInt("grid", 0);
        if (!prefs.getBoolean("grid2", false)) {
            if (g >= 2) {
                g++;
            }
            prefs.edit().putInt("grid", g).putBoolean("grid2", true).apply();
        }
        grid = Grid.values()[Math.max(0, Math.min(Grid.values().length - 1, g))];
        histo = Histo.values()[Math.max(0, Math.min(Histo.values().length - 1, prefs.getInt("histo", 0)))];
        levelEnabled = prefs.getBoolean("level", false);
        warningsEnabled = prefs.getBoolean("warnings", true);
        shotsEnabled = prefs.getBoolean("shots", true);
        zebraIdx = clamp(prefs.getInt("zebra", 0), ZEBRA_IRE.length);
        peakIdx = clamp(prefs.getInt("peak", 0), PEAK_LABELS.length);
        peakColorIdx = clamp(prefs.getInt("peakcolor", 0), PEAK_COLOR_LABELS.length);
        centerMark = prefs.getBoolean("center", false);
        frame = Frame.values()[clamp(prefs.getInt("frame", 0), Frame.values().length)];
        safeIdx = clamp(prefs.getInt("safe", 0), SAFE_ZONES.length);
        mcpToken = prefs.getString("mcptoken", null);
        standbyMinutes = prefs.getInt("standby", -1);
    }

    public int standbyMinutes() { return standbyMinutes; }

    public Beep.Mode beepMode() { return beepMode; }

    /** Remote control in the browser: live view, settings, shutter. Protected by a code shown on the camera. */
    public boolean remoteEnabled() { return remoteEnabled; }

    public String remoteCode() { return remoteCode; }

    /** MCP interface for AI assistants in the home network, protected by its own token */
    public boolean mcpEnabled() { return mcpEnabled; }

    public String mcpToken() { return mcpToken; }

    /** 8 characters without easily confused ones; only used in the home network */
    @android.annotation.SuppressLint("TrulyRandom")
    private static String newToken() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        java.util.Random r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            b.append(chars.charAt(r.nextInt(chars.length())));
        }
        return b.toString();
    }

    private static String standbyText(int m) {
        return m < 0 ? "wie Kamera" : m == 0 ? "nie" : "nach " + m + " min";
    }

    /** true: the app controls AF and capture (AF frames and beep work); false: the camera does it directly */
    public boolean appShutter() { return appShutter; }

    public boolean wifiEnabled() { return wifiEnabled; }

    public Grid grid() { return grid; }

    private static int clamp(int i, int n) {
        return Math.max(0, Math.min(n - 1, i));
    }

    /** Zebra threshold as luma 0..255 of the full range live frames, 0 = off */
    public int zebraLevel() {
        int ire = ZEBRA_IRE[zebraIdx];
        return ire == 0 ? 0 : ire > 100 ? 254 : Math.min(250, Math.round(ire * 2.55f));
    }

    public String zebraLabel() {
        int ire = ZEBRA_IRE[zebraIdx];
        return ire == 0 ? "Aus" : ire > 100 ? "100+ IRE" : ire + " IRE";
    }

    public int peakingLevel() { return PEAK_THRESHOLDS[peakIdx]; }

    public int peakingColor() { return PEAK_COLORS[peakColorIdx]; }

    public boolean centerMark() { return centerMark; }

    public Frame frame() { return frame; }

    /** Safe zone in percent of the picture, 0 = off */
    public int safeZone() { return SAFE_ZONES[safeIdx]; }

    /** Zebra and peaking are drawn over the live picture only */
    public boolean liveAnalysisVisible() {
        return state == State.LIVE || state == State.QUICK || state == State.AF_MOVE;
    }

    private void toggleZebra() {
        if (zebraIdx == 0) {
            zebraIdx = clamp(prefs.getInt("zebralast", 7), ZEBRA_IRE.length);
            if (zebraIdx == 0) {
                zebraIdx = 7;
            }
        } else {
            prefs.edit().putInt("zebralast", zebraIdx).apply();
            zebraIdx = 0;
        }
        prefs.edit().putInt("zebra", zebraIdx).apply();
        showMessage("Zebra: " + zebraLabel(), false);
        host.applyDisplay();
    }

    private void togglePeaking() {
        if (peakIdx == 0) {
            peakIdx = clamp(prefs.getInt("peaklast", 2), PEAK_LABELS.length);
            if (peakIdx == 0) {
                peakIdx = 2;
            }
        } else {
            prefs.edit().putInt("peaklast", peakIdx).apply();
            peakIdx = 0;
        }
        prefs.edit().putInt("peak", peakIdx).apply();
        showMessage("Fokus-Peaking: " + PEAK_LABELS[peakIdx], false);
        host.applyDisplay();
    }
    public Histo histo() { return histo; }
    public boolean levelEnabled() { return levelEnabled; }
    public boolean warningsEnabled() { return warningsEnabled; }
    public boolean shotsEnabled() { return shotsEnabled; }
    public float roll() { return roll; }

    private long levelSince;

    /** Called when the level sensor is switched on */
    public void markLevelStart() {
        levelSince = SystemClock.uptimeMillis();
        roll = Float.NaN;
        pitch = Float.NaN;
    }

    /** The sensor was switched on a while ago but has not delivered anything */
    public boolean levelSilent() {
        return levelEnabled && Float.isNaN(roll) && levelSince > 0 && SystemClock.uptimeMillis() - levelSince > 3000;
    }

    private int remainingShots = -1;

    public int remainingShots() { return remainingShots; }

    public void setRemainingShots(int n) {
        if (n != remainingShots) {
            remainingShots = n;
            host.redraw();
        }
    }
    public float pitch() { return pitch; }

    /** New tilt values; redraws only when the change is visible */
    public void setTilt(float r, float p) {
        boolean visible = Float.isNaN(roll) || Math.abs(r - roll) >= 0.3f || Math.abs(p - pitch) >= 0.5f;
        roll = r;
        pitch = p;
        if (visible && levelEnabled && state == State.LIVE) {
            host.redraw();
        }
    }

    public boolean wifiConnected() { return host.wifiConnected(); }

    public CameraClock clock() { return host.clock(); }
    public boolean debugEnabled() { return debugEnabled; }

    public void setBattery(int level, boolean plugged) {
        batteryLevel = level;
        batteryPlugged = plugged;
        host.redraw();
    }

    public int batteryLevel() { return batteryLevel; }

    public void setWriting(boolean w) {
        writing = w;
        host.redraw();
    }

    public boolean writing() { return writing; }
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
        if (focusResult != FocusResult.OK && beepMode != Beep.Mode.OFF) {
            host.playFocusBeep(beepMode);
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

    /** Rows of the open sub list or of the current tab */
    public List<Row> rows() {
        if (sub != null) {
            List<Row> r = sub.rows();
            return r.isEmpty() ? java.util.Collections.singletonList(Row.info("(leer)", "")) : r;
        }
        List<Row> out = new ArrayList<Row>();
        switch (tab) {
            case TAB_MAIN:
                mainRows(out);
                break;
            case TAB_SHOOTING:
                group(out, "Bildqualität", "quality", "size", "aspect");
                group(out, "Bildfolgemodus", "drive", "bracketstep", "bracketcount", "bracketlevel", "bracketorder");
                sequenceRows(out);
                processRows(out);
                group(out, "Aufnahme", "lenr");
                displayRows(out);
                break;
            case TAB_EXPOSURE:
                group(out, "Belichtung", "iso", "ev");
                autoIsoRows(out);
                group(out, "Messung", "metering");
                if (!Settings.flashReady) {
                    out.add(Row.header("Blitz"));
                    out.add(Row.info("Blitz nicht bereit", "Blitz ausklappen"));
                    groupRows(out, null, "redeye");
                } else {
                    group(out, "Blitz", "flash", "flashcomp", "redeye");
                }
                group(out, "Weißabgleich", "wb", "wbtemp", "wbab", "wbgm");
                group(out, "Farbe/Farbton", "dro", "look", "style", "contrast", "saturation", "sharpness", "effect", "softskin", "colorspace");
                break;
            case TAB_FOCUS:
                group(out, "AF/MF", "afmf", "afmode");
                group(out, "Fokusfeld", "afarea", "spotsize");
                if (isFlexSpot()) {
                    out.add(Row.action("Fokusfeld verschieben", "", new Runnable() {
                        @Override
                        public void run() {
                            state = State.AF_MOVE;
                        }
                    }));
                    out.add(Row.action("Fokusfeld zur Mitte", "", new Runnable() {
                        @Override
                        public void run() {
                            session.centerFocusPoint();
                            showMessage("Fokusfeld in der Mitte", false);
                        }
                    }));
                }
                group(out, "Motiverkennung", "face");
                out.add(Row.header("Fokus-Assistent"));
                out.add(Row.info("Fokuslupe", "Benutzertaste (Standard: C1)"));
                out.add(Row.action("MF-Hilfe (Lupe am Fokusring)", mfAssistText(mfAssist()), new Runnable() {
                    @Override
                    public void run() {
                        int i = 0;
                        while (i < MF_ASSIST_TIMES.length && MF_ASSIST_TIMES[i] != mfAssist()) {
                            i++;
                        }
                        prefs.edit().putInt("mfassist", MF_ASSIST_TIMES[(i + 1) % MF_ASSIST_TIMES.length]).apply();
                    }
                }));
                break;
            case TAB_NETWORK:
                networkRows(out);
                break;
            case TAB_SETUP:
                setupRows(out);
                break;
            case TAB_MYMENU:
                myMenuRows(out);
                break;
            default:
                break;
        }
        if (out.isEmpty()) {
            out.add(Row.info("Keine Einstellungen verfügbar", ""));
        }
        return out;
    }

    /** Title of the open sub list, null on the tab level */
    public String subTitle() {
        return sub != null ? sub.title() : null;
    }

    /** Header plus the settings that exist and matter now; nothing if none of them does */
    private void group(List<Row> out, String title, String... ids) {
        groupRows(out, title, ids);
    }

    private void groupRows(List<Row> out, String title, String... ids) {
        CameraSession.Params p = session.current();
        List<Row> rows = new ArrayList<Row>();
        for (String id : ids) {
            Setting s = Settings.get(id);
            if (s != null && s.isShown(p)) {
                rows.add(Row.of(s));
            }
        }
        if (!rows.isEmpty()) {
            if (title != null) {
                out.add(Row.header(title));
            }
            out.addAll(rows);
        }
    }

    /** Main tab: the most important shooting settings at a glance, each can be changed here */
    private void mainRows(List<Row> out) {
        out.add(Row.info("Modus · Zeit · Blende", modeName() + "  ·  " + session.shutterText() + "  ·  " + session.apertureText()));
        out.add(Row.info("Restaufnahmen", remainingShots >= 0 ? String.valueOf(remainingShots) : "–"));
        out.add(Row.sub("Objektiv", lensSummary(), new Sub() {
            @Override
            public String title() {
                return "Objektiv";
            }

            @Override
            public List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                try {
                    for (String[] d : session.lensDetails()) {
                        r.add(Row.info(d[0], d[1]));
                    }
                } catch (Throwable t) {
                    Log.error("lens details", t);
                    r.add(Row.info("Objektiv", "Daten nicht lesbar"));
                }
                return r;
            }
        }));
        groupRows(out, null, "ev", "iso", "quality", "aspect", "drive", "flash", "metering", "wb", "dro", "look", "style",
                "afmf", "afmode", "afarea");
    }

    /** ISO automatic with minimum shutter speed (ISO AUTO Min. SS of newer Sony bodies) */
    private void autoIsoRows(List<Row> out) {
        if (autoIso == null) {
            return;
        }
        out.add(Row.header("ISO-Automatik mit Mindestzeit (App)"));
        final int m = autoIso.mode();
        out.add(Row.action("Mindestverschlusszeit", AutoIso.LABELS[m], new Runnable() {
            @Override
            public void run() {
                autoIso.setMode((autoIso.mode() + 1) % AutoIso.LABELS.length);
            }
        }));
        if (m == 0) {
            out.add(Row.info("Grenze nach Brennweite", "1/(1,5 × Brennweite), Standard"));
            return;
        }
        out.add(Row.info("Grenze jetzt", autoIso.limitText()
                + (session.focalLength() > 0 ? " bei " + Math.round(session.focalLength()) + " mm" : "")));
        out.add(Row.action("ISO mindestens", String.valueOf(autoIso.isoMin()), new Runnable() {
            @Override
            public void run() {
                autoIso.setRange(nextIso(autoIso.isoMin(), autoIso.isoMax()), autoIso.isoMax());
            }
        }));
        out.add(Row.action("ISO höchstens", String.valueOf(autoIso.isoMax()), new Runnable() {
            @Override
            public void run() {
                int i = 0;
                while (i < AutoIso.ISO_STEPS.length && AutoIso.ISO_STEPS[i] <= autoIso.isoMax()) {
                    i++;
                }
                int n = AutoIso.ISO_STEPS[i % AutoIso.ISO_STEPS.length];
                autoIso.setRange(autoIso.isoMin(), Math.max(n, autoIso.isoMin()));
            }
        }));
        out.add(Row.action("Bildstabilisator (OSS) nutzen", autoIso.ossBonus() ? "1 Stufe länger" : "Aus", new Runnable() {
            @Override
            public void run() {
                autoIso.setOssBonus(!autoIso.ossBonus());
            }
        }));
        out.add(Row.info("Zustand", autoIso.state()));
    }

    /** Next ISO step for the minimum, wrapping to 100, never above the maximum */
    private static int nextIso(int cur, int max) {
        for (int v : AutoIso.ISO_STEPS) {
            if (v > cur && v <= max) {
                return v;
            }
        }
        return AutoIso.ISO_STEPS[0];
    }

    /** Stage 3c: RAW files converted and combined by the app, experiments with the camera's filters */
    private void processRows(List<Row> out) {
        if (processor == null) {
            return;
        }
        out.add(Row.header("Verarbeitung (RAW)"));
        Processor.Kind auto = autoExport();
        out.add(Row.action("Zusätzlich speichern als", auto == null ? "Aus"
                : auto == Processor.Kind.PNG ? "PNG " + pngBits() + " Bit" : auto.label, new Runnable() {
            @Override
            public void run() {
                int i = (prefs.getInt("autoexport", 0) + 1) % 4;
                prefs.edit().putInt("autoexport", i).apply();
                processor.markSeen();
            }
        }));
        out.add(Row.action("PNG-Farbtiefe", pngBits() + " Bit", new Runnable() {
            @Override
            public void run() {
                prefs.edit().putInt("pngbits", pngBits() == 16 ? 8 : 16).apply();
            }
        }));
        out.add(Row.sub("Letztes RAW umwandeln", "DNG, TIFF, PNG", new Sub() {
            @Override
            public String title() {
                return "Letztes RAW umwandeln";
            }

            @Override
            public List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                List<java.io.File> last = Processor.newest(1);
                r.add(Row.info("Datei", last.isEmpty() ? "keine ARW auf der Karte" : last.get(0).getName()));
                for (final Processor.Kind k : new Processor.Kind[]{Processor.Kind.DNG, Processor.Kind.TIFF, Processor.Kind.PNG}) {
                    String lbl = k == Processor.Kind.PNG ? "PNG " + pngBits() + " Bit" : k.label;
                    String hint = k == Processor.Kind.DNG ? "verlustfrei" : k == Processor.Kind.HEIC || k == Processor.Kind.AVIF
                            ? "8 Bit, dauert Minuten" : "entwickelt, sRGB";
                    r.add(Row.action("als " + lbl, hint, new Runnable() {
                        @Override
                        public void run() {
                            String msg = processor.add(k, Processor.newest(1));
                            showMessage(msg, msg.startsWith("Achtung"));
                        }
                    }));
                }
                return r;
            }
        }));
        out.add(Row.sub("HEIC/AVIF (Versuch)", heifQuality() + " % · " + HEIF_SIZES[heifSizeIndex()], new Sub() {
            @Override
            public String title() {
                return "HEIC/AVIF (Versuch)";
            }

            @Override
            public List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                r.add(Row.action("Qualität", heifQuality() + " %", new Runnable() {
                    @Override
                    public void run() {
                        int q = heifQuality(), n = HEIF_QUALITIES[0];
                        for (int v : HEIF_QUALITIES) {
                            if (v > q) {
                                n = v;
                                break;
                            }
                        }
                        setHeif(n, -1, 0);
                    }
                }));
                r.add(Row.action("Bildgröße", HEIF_SIZES[heifSizeIndex()], new Runnable() {
                    @Override
                    public void run() {
                        setHeif(0, (heifSizeIndex() + 1) % HEIF_SIZES.length, 0);
                    }
                }));
                r.add(Row.action("Kerne für AVIF", avifThreads() == 4 ? "4 (kaum schneller)" : "2", new Runnable() {
                    @Override
                    public void run() {
                        setHeif(0, -1, avifThreads() == 4 ? 2 : 4);
                    }
                }));
                r.add(Row.info("Dauer etwa", "HEIC " + processor.estimateMinutes(Processor.Kind.HEIC) + " min, AVIF "
                        + processor.estimateMinutes(Processor.Kind.AVIF) + " min"));
                for (final Processor.Kind k : new Processor.Kind[]{Processor.Kind.HEIC, Processor.Kind.AVIF}) {
                    r.add(Row.action("Letztes RAW als " + (k == Processor.Kind.HEIC ? "HEIC" : "AVIF"),
                            k == Processor.Kind.HEIC ? ".HIF" : ".AVF", new Runnable() {
                        @Override
                        public void run() {
                            String msg = processor.add(k, Processor.newest(1));
                            showMessage(msg, msg.startsWith("Achtung"));
                        }
                    }));
                }
                return r;
            }
        }));
        out.add(Row.action("Verrechnen: Anzahl Bilder", String.valueOf(combineCount()), new Runnable() {
            @Override
            public void run() {
                int n = combineCount(), i = 0;
                while (i < COMBINE_COUNTS.length && COMBINE_COUNTS[i] <= n) {
                    i++;
                }
                prefs.edit().putInt("combinecount", COMBINE_COUNTS[i % COMBINE_COUNTS.length]).apply();
            }
        }));
        out.add(Row.sub("Letzte " + combineCount() + " Bilder verrechnen", "als DNG", new Sub() {
            @Override
            public String title() {
                return "Letzte " + combineCount() + " RAWs verrechnen";
            }

            @Override
            public List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                final List<java.io.File> files = Processor.newest(combineCount());
                r.add(Row.info("Bilder", files.isEmpty() ? "keine ARW auf der Karte"
                        : files.get(0).getName() + " – " + files.get(files.size() - 1).getName()));
                Object[][] kinds = {
                    {Processor.Kind.MEAN, "weniger Rauschen"},
                    {Processor.Kind.SUM, "aufhellen"},
                    {Processor.Kind.MAX, "Sternspuren, Lichtspuren"},
                    {Processor.Kind.FOCUS, "vom Stativ"}};
                for (Object[] k : kinds) {
                    final Processor.Kind kind = (Processor.Kind) k[0];
                    r.add(Row.action(kind.label, (String) k[1], new Runnable() {
                        @Override
                        public void run() {
                            showMessage(processor.add(kind, Processor.newest(combineCount())), false);
                        }
                    }));
                }
                return r;
            }
        }));
        out.add(Row.sub("Kamera-Verarbeitung (Versuch)", "Bildprozessor", new Sub() {
            @Override
            public String title() {
                return "Kamera-Verarbeitung (Versuch)";
            }

            @Override
            public List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                final RawLab lab = session.rawLab();
                r.add(Row.info("Letzter Versuch", lab.result()));
                Object[][] modes = {
                    {"durchreiche", "RAW-Übernahme testen", "1 Bild"},
                    {"addition", "Mehrbild-Rauschminderung", Math.min(8, combineCount()) + " Bilder"},
                    {"hdr", "HDR (0, −2, +2 EV)", "3 Bilder"}};
                for (final Object[] m : modes) {
                    r.add(Row.action((String) m[1], (String) m[2], new Runnable() {
                        @Override
                        public void run() {
                            int n = "hdr".equals(m[0]) ? 2 : Math.min(8, combineCount());
                            showMessage(lab.start((String) m[0], n), false);
                            state = State.LIVE;
                            sub = null;
                        }
                    }));
                }
                return r;
            }
        }));
        String pr = processor.progress();
        if (pr != null) {
            out.add(Row.info("Läuft", pr));
            out.add(Row.action("Verarbeitung abbrechen", "", new Runnable() {
                @Override
                public void run() {
                    processor.cancelAll();
                    showMessage("Verarbeitung wird abgebrochen", false);
                }
            }));
        }
    }

    /** Time-lapse modelled on the LRTimelapse Pro Timer, with interval ramping and Holy Grail */
    private void timelapseRows(List<Row> out, final Sequencer q) {
        final boolean run = q.running() == Sequencer.Mode.TIMELAPSE;
        if (run) {
            out.add(Row.info("Läuft", q.status()));
            out.add(Row.action("Intervall-Rampe starten", "→ " + Sequencer.fmtSec(q.get("tl.rampto", 150) / 10.0)
                    + " in " + q.get("tl.rampmin", 20) + " min", new Runnable() {
                @Override
                public void run() {
                    showMessage(q.tlRamp(q.get("tl.rampto", 150) / 10.0, q.get("tl.rampmin", 20)), false);
                }
            }));
            out.add(Row.action("Rampe anhalten", "", new Runnable() {
                @Override
                public void run() {
                    showMessage(q.tlRampStop(), false);
                }
            }));
            if (q.grail() != null) {
                out.add(Row.action("Holy Grail: Referenz heller", "+1/3 EV", new Runnable() {
                    @Override
                    public void run() {
                        showMessage(q.tlReference(1), false);
                    }
                }));
                out.add(Row.action("Holy Grail: Referenz dunkler", "−1/3 EV", new Runnable() {
                    @Override
                    public void run() {
                        showMessage(q.tlReference(-1), false);
                    }
                }));
            }
        }
        out.add(cycle("Intervall", "tl.interval", Sequencer.TL_INTERVALS, 50, new Fmt() {
            public String f(int v) { return Sequencer.fmtSec(v / 10.0); }
        }));
        out.add(cycle("Anzahl der Aufnahmen", "tl.count", Sequencer.TL_COUNTS, 0, new Fmt() {
            public String f(int v) { return v == 0 ? "unbegrenzt" : String.valueOf(v); }
        }));
        out.add(cycle("Belichtung", "tl.bulb", Sequencer.TL_BULB, 0, new Fmt() {
            public String f(int v) { return v == 0 ? "Kamera (M)" : "BULB " + Sequencer.duration(v); }
        }));
        out.add(cycle("Start", "tl.at", new int[]{0, 1}, 0, new Fmt() {
            public String f(int v) { return v == 0 ? "nach Verzögerung" : "um Uhrzeit"; }
        }));
        if (q.get("tl.at", 0) == 0) {
            out.add(cycle("Startverzögerung", "tl.delay", Sequencer.START_DELAYS, 0, new Fmt() {
                public String f(int v) { return Sequencer.duration(v); }
            }));
        } else {
            out.add(cycle("Startzeit Stunde", "tl.ath", range(0, 23), 20, new Fmt() {
                public String f(int v) { return String.format(java.util.Locale.US, "%02d:%02d Uhr", v, q.get("tl.atm", 0)); }
            }));
            out.add(cycle("Startzeit Minute", "tl.atm", new int[]{0, 15, 30, 45}, 0, new Fmt() {
                public String f(int v) { return String.format(java.util.Locale.US, ":%02d", v); }
            }));
        }
        out.add(cycle("Tägliches Zeitfenster", "tl.win", new int[]{0, 1}, 0, new Fmt() {
            public String f(int v) { return v == 0 ? "Aus" : "Ein"; }
        }));
        if (q.get("tl.win", 0) == 1) {
            out.add(cycle("Zeitfenster von", "tl.wfrom", range(0, 23), 8, new Fmt() {
                public String f(int v) { return v + ":00 Uhr"; }
            }));
            out.add(cycle("Zeitfenster bis", "tl.wto", range(0, 23), 18, new Fmt() {
                public String f(int v) { return v + ":00 Uhr"; }
            }));
            out.add(cycle("Wochentage", "tl.wdays", Sequencer.TL_DAYS, 127, new Fmt() {
                public String f(int v) { return v == 127 ? "täglich" : v == 31 ? "Mo–Fr" : "Sa–So"; }
            }));
        }
        out.add(cycle("Holy Grail (Belichtung nachführen)", "tl.hg", new int[]{0, 1, 2}, 0, new Fmt() {
            public String f(int v) { return v == 0 ? "Aus" : v == 1 ? "Sonnenuntergang" : "Sonnenaufgang"; }
        }));
        if (q.get("tl.hg", 0) != 0) {
            out.add(cycle("ISO höchstens", "tl.isomax", Sequencer.TL_ISO_MAX, 3200, new Fmt() {
                public String f(int v) { return String.valueOf(v); }
            }));
            out.add(cycle("ISO mindestens", "tl.isomin", Sequencer.TL_ISO_MIN, 100, new Fmt() {
                public String f(int v) { return String.valueOf(v); }
            }));
            out.add(cycle("Mindestpause zwischen Bildern", "tl.dark", Sequencer.TL_DARK, 2, new Fmt() {
                public String f(int v) { return v + " s (längste Zeit = Intervall − Pause)"; }
            }));
            out.add(Row.info("Voraussetzung", "Modus M, RAW oder JPEG, fester Weißabgleich"));
        }
        out.add(cycle("Rampe: Ziel-Intervall", "tl.rampto", Sequencer.TL_INTERVALS, 150, new Fmt() {
            public String f(int v) { return Sequencer.fmtSec(v / 10.0); }
        }));
        out.add(cycle("Rampe: Dauer", "tl.rampmin", Sequencer.TL_RAMP_MIN, 20, new Fmt() {
            public String f(int v) { return v + " min"; }
        }));
        out.add(cycle("Vorschaufilm", "tl.film", Sequencer.FILM_FPS, 0, new Fmt() {
            public String f(int v) { return v == 0 ? "Aus" : v + " Bilder/s"; }
        }));
        out.add(Row.info("Protokoll", "OMKAMERA/ZRnnnn.LOG"));
    }

    private static int[] range(int a, int b) {
        int[] r = new int[b - a + 1];
        for (int i = 0; i < r.length; i++) {
            r[i] = a + i;
        }
        return r;
    }

    /** Sequences run by the app: interval, focus bracket, extended bracket, BULB timer */
    private void sequenceRows(List<Row> out) {
        if (sequencer == null) {
            return;
        }
        final Sequencer q = sequencer;
        out.add(Row.header("Aufnahmeablauf (App)"));
        out.add(Row.action("Ablauf beim Auslösen", q.mode().label, new Runnable() {
            @Override
            public void run() {
                Sequencer.Mode[] all = Sequencer.Mode.values();
                q.setMode(all[(q.mode().ordinal() + 1) % all.length]);
            }
        }));
        switch (q.mode()) {
            case INTERVAL:
                out.add(cycle("Startzeit", "iv.start", Sequencer.START_DELAYS, 0, new Fmt() {
                    public String f(int v) { return Sequencer.duration(v); }
                }));
                out.add(cycle("Aufnahmeintervall", "iv.interval", Sequencer.INTERVALS, 5, new Fmt() {
                    public String f(int v) { return Sequencer.duration(v); }
                }));
                out.add(cycle("Anzahl der Aufnahmen", "iv.count", Sequencer.COUNTS, 100, new Fmt() {
                    public String f(int v) { return String.valueOf(v); }
                }));
                out.add(cycle("Scharfstellen", "iv.af", new int[]{0, 1}, 0, new Fmt() {
                    public String f(int v) { return v == 0 ? "einmal am Anfang" : "vor jeder Aufnahme"; }
                }));
                out.add(cycle("Belichtung festhalten", "iv.ael", new int[]{0, 1}, 0, new Fmt() {
                    public String f(int v) { return v == 1 ? "Ein (AEL)" : "Aus (nachführen)"; }
                }));
                out.add(cycle("Zeitrafferfilm", "iv.film", Sequencer.FILM_FPS, 25, new Fmt() {
                    public String f(int v) { return v == 0 ? "Aus" : v + " Bilder/s"; }
                }));
                break;
            case TIMELAPSE:
                timelapseRows(out, q);
                break;
            case FOCUS:
                if (!session.focusDriveSupported()) {
                    out.add(Row.info("An dieser Kamera nicht möglich", "kein Fokusantrieb für Apps"));
                    break;
                }
                out.add(cycle("Schrittweite", "fb.step", Sequencer.FOCUS_STEPS, 3, new Fmt() {
                    public String f(int v) { return String.valueOf(v); }
                }));
                out.add(cycle("Anzahl der Aufnahmen", "fb.count", Sequencer.FOCUS_COUNTS, 10, new Fmt() {
                    public String f(int v) { return String.valueOf(v); }
                }));
                out.add(cycle("Pause zwischen Aufnahmen", "fb.pause", Sequencer.FOCUS_PAUSE, 0, new Fmt() {
                    public String f(int v) { return v == 0 ? "kürzeste" : v + " s"; }
                }));
                break;
            case BRACKET:
                out.add(cycle("Anzahl der Aufnahmen", "eb.count", Sequencer.BRACKET_COUNTS, 5, new Fmt() {
                    public String f(int v) { return v + " Bilder"; }
                }));
                out.add(cycle("Belichtungsstufe", "eb.step", Sequencer.BRACKET_STEPS, 3, new Fmt() {
                    public String f(int v) { return Sequencer.ev(v); }
                }));
                out.add(cycle("Reihenfolge", "eb.order", new int[]{0, 1}, 0, new Fmt() {
                    public String f(int v) { return v == 0 ? "0 → − → +" : "− → 0 → +"; }
                }));
                break;
            case BULB:
                out.add(cycle("Belichtungszeit", "bt.time", Sequencer.BULB_TIMES, 60, new Fmt() {
                    public String f(int v) { return Sequencer.duration(v); }
                }));
                out.add(Row.info("Voraussetzung", "Modus M, Verschlusszeit BULB"));
                break;
            default:
                break;
        }
        if (q.mode() != Sequencer.Mode.OFF) {
            out.add(Row.info("Auslöser startet, erneut drücken bricht ab", ""));
        }
    }

    private static String mfAssistText(int v) {
        return v == 0 ? "Aus" : v < 0 ? "Ein, bleibt bis Tastendruck" : "Ein, " + v + " s nach dem Drehen";
    }

    private String lensSummary() {
        String t = session.lensText();
        return t != null ? t : "keine Daten";
    }

    interface Fmt {
        String f(int v);
    }

    private Row cycle(String label, final String key, final int[] values, final int def, Fmt fmt) {
        return Row.action(label, fmt.f(sequencer.get(key, def)), new Runnable() {
            @Override
            public void run() {
                sequencer.cycle(key, values, def);
            }
        });
    }

    private void displayRows(List<Row> out) {
        out.add(Row.header("Aufnahme-Anzeige"));
        out.add(Row.action("Gitterlinien", grid.label, new Runnable() {
            @Override
            public void run() {
                grid = Grid.values()[(grid.ordinal() + 1) % Grid.values().length];
                prefs.edit().putInt("grid", grid.ordinal()).apply();
            }
        }));
        out.add(Row.action("Histogramm", histo.label, new Runnable() {
            @Override
            public void run() {
                histo = Histo.values()[(histo.ordinal() + 1) % Histo.values().length];
                prefs.edit().putInt("histo", histo.ordinal()).apply();
                host.applyDisplay();
            }
        }));
        out.add(Row.action("Mittelmarkierung", centerMark ? "Ein" : "Aus", new Runnable() {
            @Override
            public void run() {
                centerMark = !centerMark;
                prefs.edit().putBoolean("center", centerMark).apply();
            }
        }));
        out.add(Row.action("Formatmarkierung", frame.label, new Runnable() {
            @Override
            public void run() {
                frame = Frame.values()[(frame.ordinal() + 1) % Frame.values().length];
                prefs.edit().putInt("frame", frame.ordinal()).apply();
            }
        }));
        out.add(Row.action("Sichere Zone", safeIdx == 0 ? "Aus" : SAFE_ZONES[safeIdx] + " %", new Runnable() {
            @Override
            public void run() {
                safeIdx = (safeIdx + 1) % SAFE_ZONES.length;
                prefs.edit().putInt("safe", safeIdx).apply();
            }
        }));
        out.add(Row.action("Wasserwaage", levelEnabled ? "Ein" : "Aus", new Runnable() {
            @Override
            public void run() {
                levelEnabled = !levelEnabled;
                prefs.edit().putBoolean("level", levelEnabled).apply();
                host.applyDisplay();
            }
        }));
        out.add(Row.action("Fokusfelder anzeigen", framesEnabled ? "Ein" : "Aus", new Runnable() {
            @Override
            public void run() {
                framesEnabled = !framesEnabled;
                prefs.edit().putBoolean("frames", framesEnabled).apply();
            }
        }));
        out.add(Row.action("Warnhinweise", warningsEnabled ? "Ein" : "Aus", new Runnable() {
            @Override
            public void run() {
                warningsEnabled = !warningsEnabled;
                prefs.edit().putBoolean("warnings", warningsEnabled).apply();
            }
        }));
        out.add(Row.action("Restaufnahmen anzeigen", shotsEnabled ? "Ein" : "Aus", new Runnable() {
            @Override
            public void run() {
                shotsEnabled = !shotsEnabled;
                prefs.edit().putBoolean("shots", shotsEnabled).apply();
            }
        }));
        out.add(Row.header("Belichtungs- und Fokushilfen"));
        out.add(Row.action("Zebra", zebraLabel(), new Runnable() {
            @Override
            public void run() {
                zebraIdx = (zebraIdx + 1) % ZEBRA_IRE.length;
                prefs.edit().putInt("zebra", zebraIdx).apply();
                host.applyDisplay();
            }
        }));
        out.add(Row.action("Fokus-Peaking", PEAK_LABELS[peakIdx], new Runnable() {
            @Override
            public void run() {
                peakIdx = (peakIdx + 1) % PEAK_LABELS.length;
                prefs.edit().putInt("peak", peakIdx).apply();
                host.applyDisplay();
            }
        }));
        if (peakIdx > 0) {
            out.add(Row.action("Peaking-Farbe", PEAK_COLOR_LABELS[peakColorIdx], new Runnable() {
                @Override
                public void run() {
                    peakColorIdx = (peakColorIdx + 1) % PEAK_COLOR_LABELS.length;
                    prefs.edit().putInt("peakcolor", peakColorIdx).apply();
                }
            }));
        }
    }

    private void networkRows(List<Row> out) {
        out.add(Row.header("WLAN"));
        out.add(Row.action("WLAN in der App", wifiEnabled ? "Ein" : "Aus", new Runnable() {
            @Override
            public void run() {
                wifiEnabled = !wifiEnabled;
                if (!wifiEnabled) {
                    debugEnabled = false;
                    remoteEnabled = false;
                    mcpEnabled = false;
                }
                prefs.edit().putBoolean("wifi", wifiEnabled).putBoolean("debug", debugEnabled)
                        .putBoolean("remote", remoteEnabled).putBoolean("mcp", mcpEnabled).apply();
                host.applyNetwork();
            }
        }));
        if (wifiEnabled) {
            String[] d = host.wifiDetails();
            out.add(Row.info("Status", host.wifiStatus()));
            if (d != null) {
                out.add(Row.info("Netz (SSID)", d[0]));
                out.add(Row.info("Standard", d[1]));
                out.add(Row.info("Geschwindigkeit", d[2]));
                out.add(Row.info("Signal", d[3]));
                out.add(Row.info("IP-Adresse", d[4]));
            }
        }
        out.add(Row.header("Fernzugriff"));
        out.add(Row.action("Fernsteuerung (Browser)", remoteEnabled ? "Ein · Code " + remoteCode : "Aus", new Runnable() {
            @Override
            public void run() {
                remoteEnabled = !remoteEnabled;
                if (remoteEnabled) {
                    // New code every time remote control is switched on
                    remoteCode = String.valueOf(1000 + new java.util.Random().nextInt(9000));
                    wifiEnabled = true;
                    debugEnabled = true;
                }
                prefs.edit().putBoolean("remote", remoteEnabled).putString("remotecode", remoteCode)
                        .putBoolean("wifi", wifiEnabled).putBoolean("debug", debugEnabled).apply();
                host.applyNetwork();
            }
        }));
        out.add(Row.action("MCP-Schnittstelle (KI)", mcpEnabled ? "Ein · Token " + mcpToken : "Aus", new Runnable() {
            @Override
            public void run() {
                mcpEnabled = !mcpEnabled;
                if (mcpEnabled) {
                    // The token stays the same, so the PC side does not need to be changed again
                    if (mcpToken == null) {
                        mcpToken = newToken();
                    }
                    wifiEnabled = true;
                    debugEnabled = true;
                }
                prefs.edit().putBoolean("mcp", mcpEnabled).putString("mcptoken", mcpToken)
                        .putBoolean("wifi", wifiEnabled).putBoolean("debug", debugEnabled).apply();
                host.applyNetwork();
            }
        }));
        if (mcpToken != null) {
            out.add(Row.action("MCP-Token erneuern", "", new Runnable() {
                @Override
                public void run() {
                    mcpToken = newToken();
                    prefs.edit().putString("mcptoken", mcpToken).apply();
                    showMessage("Neuer MCP-Token " + mcpToken, false);
                }
            }));
        }
        out.add(Row.action("Debug-Server", debugEnabled ? (host.debugAddress() != null ? host.debugAddress() : "Ein, wartet auf WLAN") : "Aus", new Runnable() {
            @Override
            public void run() {
                debugEnabled = !debugEnabled;
                if (debugEnabled) {
                    wifiEnabled = true;
                } else {
                    remoteEnabled = false;
                    mcpEnabled = false;
                }
                prefs.edit().putBoolean("wifi", wifiEnabled).putBoolean("debug", debugEnabled)
                        .putBoolean("remote", remoteEnabled).putBoolean("mcp", mcpEnabled).apply();
                host.applyNetwork();
            }
        }));
    }

    /** Camera clock: shown, set by NTP, EU summer time */
    private void clockRows(List<Row> out) {
        final CameraClock c = host.clock();
        if (c == null) {
            return;
        }
        out.add(Row.header("Uhr"));
        out.add(Row.info("Uhrzeit", CameraClock.describe()));
        out.add(Row.info("Sony-Uhr (EXIF)", CameraClock.describeCamera()));
        out.add(Row.action("Jetzt abgleichen (Zeitserver)", "", new Runnable() {
            @Override
            public void run() {
                showMessage(c.syncNtp(true), false);
            }
        }));
        out.add(Row.action("Automatisch abgleichen im WLAN", c.auto() ? "Ein" : "Aus", new Runnable() {
            @Override
            public void run() {
                c.setAuto(!c.auto());
            }
        }));
        out.add(Row.action("Zeitserver", CameraClock.SERVERS[c.server()], new Runnable() {
            @Override
            public void run() {
                c.setServer((c.server() + 1) % CameraClock.SERVERS.length);
            }
        }));
        out.add(Row.action("Sommerzeit automatisch (EU)", c.autoSummer() ? "Ein" : "Aus", new Runnable() {
            @Override
            public void run() {
                c.setAutoSummer(!c.autoSummer());
            }
        }));
        out.add(Row.info("Letzter Abgleich", c.state()));
    }

    private void setupRows(List<Row> out) {
        clockRows(out);
        out.add(Row.header("Bedienung"));
        out.add(Row.sub("Benutzertasten", "", new Sub() {
            @Override
            public String title() {
                return "Benutzertasten";
            }

            @Override
            public List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                for (final Keys.Key k : KeyFunctions.KEYS) {
                    r.add(Row.action(KeyFunctions.keyName(k), keys.get(k).label, new Runnable() {
                        @Override
                        public void run() {
                            keys.cycle(k, 1);
                        }
                    }));
                }
                r.add(Row.action("Standardbelegung", "", new Runnable() {
                    @Override
                    public void run() {
                        keys.reset();
                        showMessage("Benutzertasten zurückgesetzt", false);
                    }
                }));
                return r;
            }
        }));
        out.add(Row.sub("Fn-Raster einrichten", "", new Sub() {
            @Override
            public String title() {
                return "Fn-Raster";
            }

            @Override
            public List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                for (int i = 0; i < FnLayout.SIZE; i++) {
                    final int slot = i;
                    Setting cur = fn.get(i);
                    String pos = (i / FnLayout.COLS == 0 ? "Oben " : "Unten ") + (i % FnLayout.COLS + 1);
                    r.add(Row.pick(pos, cur != null ? cur.label : "(leer)", new Picker() {
                        @Override
                        public Setting current() {
                            return fn.get(slot);
                        }

                        @Override
                        public void picked(Setting s) {
                            fn.set(slot, s != null ? s.id : FnLayout.EMPTY);
                        }
                    }));
                }
                r.add(Row.action("Standardbelegung", "", new Runnable() {
                    @Override
                    public void run() {
                        fn.reset();
                        showMessage("Fn-Raster zurückgesetzt", false);
                    }
                }));
                return r;
            }
        }));
        out.add(Row.action("Auslöser-Steuerung", appShutter ? "App (mit Fokusanzeige)" : "Kamera direkt", new Runnable() {
            @Override
            public void run() {
                appShutter = !appShutter;
                prefs.edit().putBoolean("appshutter", appShutter).apply();
                showMessage("Wirkt nach dem nächsten Start der App", false);
            }
        }));
        out.add(Row.action("Signalton", beepMode == Beep.Mode.OFF ? "Aus" : beepMode == Beep.Mode.CAMERA ? "Kamera-Ton" : "Piepton", new Runnable() {
            @Override
            public void run() {
                beepMode = Beep.Mode.values()[(beepMode.ordinal() + 1) % 3];
                prefs.edit().putInt("beepmode", beepMode.ordinal()).apply();
                if (beepMode != Beep.Mode.OFF) {
                    host.playFocusBeep(beepMode);
                }
            }
        }));
        out.add(Row.action("Standby in der App", standbyText(standbyMinutes), new Runnable() {
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
        }));

        out.add(Row.header("Speichern/Laden"));
        for (int i = 1; i <= Memories.SLOTS; i++) {
            final int slot = i;
            out.add(Row.sub("Speicher " + i, host.memories().describe(slot), new Sub() {
                @Override
                public String title() {
                    return "Speicher " + slot;
                }

                @Override
                public List<Row> rows() {
                    List<Row> r = new ArrayList<Row>();
                    r.add(Row.info("Inhalt", host.memories().describe(slot)));
                    r.add(Row.action("Aktuelle Einstellungen hier speichern", "", new Runnable() {
                        @Override
                        public void run() {
                            int n = host.memories().save(slot);
                            showMessage("Speicher " + slot + ": " + n + " Einstellungen gesichert", false);
                        }
                    }));
                    if (host.memories().has(slot)) {
                        r.add(Row.action("Laden und anwenden", "", new Runnable() {
                            @Override
                            public void run() {
                                String res = host.memories().load(slot);
                                showMessage(res, res.contains("nicht"));
                            }
                        }));
                    }
                    return r;
                }
            }));
        }
        out.add(Row.action("Alles auf Karte sichern", "OMKAMERA/EINSTELL.JSN", new Runnable() {
            @Override
            public void run() {
                String res = host.memories().exportToCard();
                showMessage(res, !res.startsWith("Gespeichert"));
            }
        }));
        out.add(Row.action("Von Karte laden", "", new Runnable() {
            @Override
            public void run() {
                String res = host.memories().importFromCard();
                showMessage(res, !res.startsWith("Geladen"));
            }
        }));

        out.add(Row.header("Info"));
        DeviceInfo d = DeviceInfo.getInstance();
        out.add(Row.info("Modell", safe(d.getModel())));
        out.add(Row.sub("Objektiv", lensSummary(), new Sub() {
            @Override
            public String title() {
                return "Objektiv";
            }

            @Override
            public List<Row> rows() {
                List<Row> r = new ArrayList<Row>();
                try {
                    for (String[] d : session.lensDetails()) {
                        r.add(Row.info(d[0], d[1]));
                    }
                } catch (Throwable t) {
                    Log.error("lens details", t);
                    r.add(Row.info("Objektiv", "Daten nicht lesbar"));
                }
                return r;
            }
        }));
        out.add(Row.info("Firmware", safe(d.getFirmwareVersion())));
        out.add(Row.info("Plattform (API)", String.valueOf(d.getApiVersion())));
        out.add(Row.info("Android", Build.VERSION.RELEASE));
        out.add(Row.info("App-Version", BuildConfig.VERSION_NAME));
        out.add(Row.action("App beenden", "", new Runnable() {
            @Override
            public void run() {
                host.exitApp();
            }
        }));
    }

    // ---- My Menu ----

    private List<String> myMenu() {
        List<String> ids = new ArrayList<String>();
        String raw = prefs.getString("mymenu", "");
        for (String id : raw.split(",")) {
            if (id.length() > 0 && Settings.get(id) != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void saveMyMenu(List<String> ids) {
        StringBuilder b = new StringBuilder();
        for (String id : ids) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(id);
        }
        prefs.edit().putString("mymenu", b.toString()).apply();
    }

    private void myMenuRows(List<Row> out) {
        CameraSession.Params p = session.current();
        for (String id : myMenu()) {
            Setting s = Settings.get(id);
            if (s != null && s.isAvailable(p)) {
                out.add(Row.of(s));
            } else if (s != null) {
                out.add(Row.info(s.label, "gerade nicht verfügbar"));
            }
        }
        out.add(Row.pick("Posten hinzufügen …", "", new Picker() {
            @Override
            public Setting current() {
                return null;
            }

            @Override
            public void picked(Setting s) {
                if (s == null) {
                    return;
                }
                List<String> ids = myMenu();
                if (!ids.contains(s.id)) {
                    ids.add(s.id);
                    saveMyMenu(ids);
                }
            }
        }));
        out.add(Row.info("Löschtaste entfernt den gewählten Posten", ""));
    }

    private void removeFromMyMenu(Setting s) {
        List<String> ids = myMenu();
        if (ids.remove(s.id)) {
            saveMyMenu(ids);
            showMessage(s.label + " aus Mein Menü entfernt", false);
        }
    }

    public boolean isFlexSpot() {
        CameraSession.Params p = session.current();
        Setting area = Settings.get("afarea");
        if (p == null || area == null || !area.isShown(p)) {
            return false;
        }
        try {
            return Settings.isFlexSpot(p);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean aeLocked() { return aeLocked; }

    /** AEL: holds the exposure while pressed (or toggles, depending on the custom key) */
    public void onAel(boolean down) {
        if (down == aeLocked) {
            return;
        }
        CameraSession.Result r = session.setExposureLock(down);
        aeLocked = down && r.ok;
        if (down && !r.ok) {
            showMessage("AE-Speicher gesperrt" + (r.inhibition != 0 ? " (Grund " + r.inhibition + ")" : ""), true);
        }
        host.redraw();
    }

    private static String safe(String s) {
        return s == null ? "–" : s;
    }

    // ---- keys ----

    /** Returns true if the key was handled, false to leave it to the camera. */
    public boolean onKey(Keys.Key key) {
        if (key == Keys.Key.SHUTTER_HALF || key == Keys.Key.SHUTTER_FULL) {
            // Half press always returns to shooting, like on the camera itself
            if (state == State.MAGNIFY) {
                endMagnify();
            }
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
            case AF_MOVE: handled = onAfMoveKey(key); break;
            case QUICK: handled = onQuickKey(key); break;
            case MAGNIFY: handled = onMagnifyKey(key); break;
            default: handled = false;
        }
        if (handled) {
            host.redraw();
        }
        return handled;
    }

    private boolean onLiveKey(Keys.Key key) {
        if (KeyFunctions.assignable(key)) {
            return runFunction(keys.get(key), key);
        }
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

    // ---- custom keys ----

    /** Key that holds the exposure while it is pressed (function "AEL halten") */
    private Keys.Key aelHoldKey;

    public KeyFunctions keyFunctions() { return keys; }

    /** Executes the function of a custom key; returns false if the key is left to the camera */
    private boolean runFunction(KeyFunctions.Function f, Keys.Key key) {
        switch (f) {
            case NONE:
                return false;
            case AEL_HOLD:
                aelHoldKey = key;
                onAel(true);
                return true;
            case AEL_TOGGLE:
                onAel(!aeLocked);
                return true;
            case MAGNIFY:
                startMagnify(key);
                return true;
            case AF_MOVE:
                if (isFlexSpot()) {
                    state = State.AF_MOVE;
                } else {
                    showMessage("Nur mit Fokusfeld „Flex. Spot“", true);
                }
                return true;
            case AF_CENTER:
                if (isFlexSpot()) {
                    session.centerFocusPoint();
                    showMessage("Fokusfeld in der Mitte", false);
                }
                return true;
            case AF_MF: {
                Setting s = Settings.get("afmf");
                String cur = s.safeGet(session.current());
                s.write(session, "manual".equals(cur) ? "auto" : "manual");
                showMessage("Fokus: " + s.currentText(session.current()), false);
                return true;
            }
            case FN_MENU:
                state = State.FN;
                return true;
            case MY_MENU:
                openMenu();
                tab = TAB_MYMENU;
                focus = MenuFocus.LIST;
                row = 0;
                return true;
            case GRID:
                if (grid == Grid.OFF) {
                    grid = Grid.values()[Math.max(1, prefs.getInt("gridlast", 1))];
                } else {
                    prefs.edit().putInt("gridlast", grid.ordinal()).apply();
                    grid = Grid.OFF;
                }
                prefs.edit().putInt("grid", grid.ordinal()).apply();
                return true;
            case ZEBRA:
                toggleZebra();
                return true;
            case PEAKING:
                togglePeaking();
                return true;
            case HISTO:
                if (histo == Histo.OFF) {
                    histo = Histo.values()[Math.max(1, prefs.getInt("histolast", 1))];
                } else {
                    prefs.edit().putInt("histolast", histo.ordinal()).apply();
                    histo = Histo.OFF;
                }
                prefs.edit().putInt("histo", histo.ordinal()).apply();
                host.applyDisplay();
                return true;
            default: {
                Setting s = f.setting != null ? Settings.get(f.setting) : null;
                if (s == null) {
                    return false;
                }
                if (!s.isAvailable(session.current())) {
                    showMessage(s.label + ": gerade nicht verfügbar", true);
                    return true;
                }
                quick = s;
                quickKey = key;
                state = State.QUICK;
                return true;
            }
        }
    }

    /** A custom key was released (for "AEL halten") */
    public void onKeyRelease(Keys.Key key) {
        if (key == aelHoldKey) {
            aelHoldKey = null;
            onAel(false);
        }
    }

    public Setting quickSetting() { return quick; }

    private boolean onQuickKey(Keys.Key key) {
        int dir = Keys.direction(key);
        if (key == Keys.Key.LEFT || key == Keys.Key.UP) dir = -1;
        if (key == Keys.Key.RIGHT || key == Keys.Key.DOWN) dir = 1;
        if (key == quickKey && key != Keys.Key.LEFT && key != Keys.Key.RIGHT && key != Keys.Key.DOWN) {
            dir = 0;
        }
        if (dir != 0) {
            stepSetting(quick, dir);
            return true;
        }
        switch (key) {
            case ENTER: case MENU: case FN: case BACK: case DISP:
            default:
                state = State.LIVE;
                return true;
        }
    }

    // ---- focus magnifier ----

    private Keys.Key magnifyKey;
    /** Focus mode before the magnifier switched to MF, null if it was MF already */
    private String focusBeforeMagnify;
    /** Magnifier was started by turning the focus ring (MF assist) */
    private boolean magnifyAuto;

    private void startMagnify(Keys.Key key) {
        magnifyKey = key;
        magnifyAuto = false;
        // The camera magnifies only in manual focus, like Sony's own MF assist: switch for a moment
        Setting afmf = Settings.get("afmf");
        String cur = afmf.safeGet(session.current());
        focusBeforeMagnify = null;
        if (cur != null && !"manual".equals(cur) && !"dmf".equals(cur)) {
            if (afmf.write(session, "manual").ok) {
                focusBeforeMagnify = cur;
            }
        }
        if (session.nextMagnification()) {
            state = State.MAGNIFY;
        } else {
            restoreFocusMode();
            showMessage("Fokuslupe an dieser Kamera nicht verfügbar", true);
        }
    }

    /** Ends the magnifier and switches back to AF if it was switched to MF for it */
    public void endMagnify() {
        session.stopMagnification();
        restoreFocusMode();
        magnifyAuto = false;
        if (state == State.MAGNIFY) {
            state = State.LIVE;
        }
        host.redraw();
    }

    private void restoreFocusMode() {
        if (focusBeforeMagnify != null) {
            Settings.get("afmf").write(session, focusBeforeMagnify);
            focusBeforeMagnify = null;
        }
    }

    // ---- MF assist: magnifier while the focus ring turns ----

    public static final int[] MF_ASSIST_TIMES = {0, 2, 5, -1};

    public int mfAssist() { return prefs.getInt("mfassist", 0); }

    private final Runnable mfAssistOff = new Runnable() {
        @Override
        public void run() {
            if (state == State.MAGNIFY && magnifyAuto) {
                endMagnify();
            }
        }
    };

    public void onFocusRing(boolean turning) {
        int mode = mfAssist();
        if (mode == 0) {
            return;
        }
        String f = Settings.get("afmf").safeGet(session.current());
        if (!"manual".equals(f) && !"dmf".equals(f)) {
            return;
        }
        host.cancel(mfAssistOff);
        if (turning) {
            if (state == State.LIVE) {
                magnifyKey = null;
                focusBeforeMagnify = null;
                if (session.nextMagnification()) {
                    magnifyAuto = true;
                    state = State.MAGNIFY;
                    host.redraw();
                }
            }
        } else if (state == State.MAGNIFY && magnifyAuto && mode > 0) {
            host.later(mfAssistOff, mode * 1000L);
        }
    }

    /** The camera ended the magnifier itself */
    public void onMagnificationChanged(boolean on) {
        if (!on && state == State.MAGNIFY) {
            restoreFocusMode();
            magnifyAuto = false;
            state = State.LIVE;
            host.redraw();
        }
    }

    private boolean onMagnifyKey(Keys.Key key) {
        switch (key) {
            case UP: session.moveMagnification(0, -1); return true;
            case DOWN: session.moveMagnification(0, 1); return true;
            case LEFT: case WHEEL_CCW: session.moveMagnification(-1, 0); return true;
            case RIGHT: case WHEEL_CW: session.moveMagnification(1, 0); return true;
            case DELETE: session.moveMagnification(0, 0); return true;
            default:
                break;
        }
        if (key == magnifyKey || key == Keys.Key.ENTER) {
            // Next level, after the last one back to the live view
            magnifyAuto = false;
            if (!session.nextMagnification()) {
                endMagnify();
            }
            return true;
        }
        if (key == Keys.Key.MENU || key == Keys.Key.BACK || key == Keys.Key.FN || key == Keys.Key.DISP) {
            endMagnify();
            return true;
        }
        return false;
    }

    private boolean onAfMoveKey(Keys.Key key) {
        CameraSession.Result r = null;
        switch (key) {
            case UP: r = session.moveFocusPoint(0, -1); break;
            case DOWN: r = session.moveFocusPoint(0, 1); break;
            case LEFT: case WHEEL_CCW: case DIAL1_CCW: r = session.moveFocusPoint(-1, 0); break;
            case RIGHT: case WHEEL_CW: case DIAL1_CW: r = session.moveFocusPoint(1, 0); break;
            case DIAL2_CCW: r = session.moveFocusPoint(0, -1); break;
            case DIAL2_CW: r = session.moveFocusPoint(0, 1); break;
            case DELETE: case C2: r = session.centerFocusPoint(); break;
            case ENTER: case BACK: case FN: case MENU: case DISP:
                state = State.LIVE;
                return true;
            default:
                return false;
        }
        if (r != null && !r.ok) {
            showMessage("Fokusfeld gesperrt" + (r.inhibition != 0 ? " (Grund " + r.inhibition + ")" : ""), true);
        }
        return true;
    }

    private void openMenu() {
        state = State.MENU;
        focus = MenuFocus.TABS;
        sub = null;
        row = 0;
    }

    /** Next selectable row (headers are skipped) */
    private int nextRow(List<Row> rows, int from, int dir) {
        int n = rows.size();
        int i = from;
        for (int k = 0; k < n; k++) {
            i = (i + dir + n) % n;
            if (!rows.get(i).header) {
                return i;
            }
        }
        return from;
    }

    private int firstRow(List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).header) {
                return i;
            }
        }
        return 0;
    }

    private boolean onMenuKey(Keys.Key key) {
        if (key == Keys.Key.MENU) {
            sub = null;
            state = State.LIVE;
            return true;
        }
        switch (focus) {
            case TABS:
                switch (key) {
                    case UP: case WHEEL_CCW: tab = (tab + TABS.length - 1) % TABS.length; row = 0; return true;
                    case DOWN: case WHEEL_CW: tab = (tab + 1) % TABS.length; row = 0; return true;
                    case RIGHT: case ENTER: focus = MenuFocus.LIST; row = firstRow(rows()); return true;
                    case BACK: case LEFT: state = State.LIVE; return true;
                    default: return false;
                }
            case LIST: {
                List<Row> rows = rows();
                row = Math.max(0, Math.min(row, rows.size() - 1));
                if (rows.get(row).header) {
                    row = nextRow(rows, row, 1);
                }
                switch (key) {
                    case UP: case WHEEL_CCW: row = nextRow(rows, row, -1); return true;
                    case DOWN: case WHEEL_CW: row = nextRow(rows, row, 1); return true;
                    case LEFT: case BACK:
                        if (sub != null) {
                            sub = null;
                            row = subParentRow;
                        } else {
                            focus = MenuFocus.TABS;
                        }
                        return true;
                    case ENTER: case RIGHT: enterRow(rows.get(row)); return true;
                    case DELETE:
                        if (sub == null && tab == TAB_MYMENU && rows.get(row).setting != null) {
                            removeFromMyMenu(rows.get(row).setting);
                            return true;
                        }
                        return false;
                    default: return false;
                }
            }
            case EDIT: {
                List<Row> rows = rows();
                row = Math.max(0, Math.min(row, rows.size() - 1));
                Setting s = rows.get(row).setting;
                if (s == null) {
                    focus = MenuFocus.LIST;
                    return true;
                }
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
                        Setting chosen = pickIndex < pickList.size() ? pickList.get(pickIndex) : null;
                        if (picker != null) {
                            picker.picked(chosen);
                        }
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
        if (r.header) {
            return;
        }
        if (r.setting != null) {
            if (r.setting.isAvailable(session.current())) {
                focus = MenuFocus.EDIT;
            } else {
                showMessage(r.setting.label + ": an dieser Kamera nicht verfügbar", true);
            }
        } else if (r.picker != null) {
            picker = r.picker;
            pickList = Settings.all();
            Setting current = r.picker.current();
            pickIndex = current != null ? pickList.indexOf(current) : pickList.size();
            focus = MenuFocus.PICK;
        } else if (r.sub != null) {
            subParentRow = row;
            sub = r.sub;
            row = firstRow(rows());
        } else if (r.action != null) {
            r.action.run();
        }
    }

    /** Moves a setting one value forward or back and reports a refusal by the camera. */
    public void stepSetting(Setting s, int direction) {
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
