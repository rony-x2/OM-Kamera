package com.github.ronyx2.omkamera;

import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;

import android.graphics.Rect;

import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.hardware.avio.DisplayManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the camera: opening, live view, reading and writing parameters, exposure values.
 * All callbacks are delivered on the main thread.
 */
public class CameraSession {

    /** Current parameters: the Android part and Sony's extension on top of it. */
    public static final class Params {
        public final Camera.Parameters params;
        public final CameraEx.ParametersModifier mod;

        Params(Camera.Parameters params, CameraEx.ParametersModifier mod) {
            this.params = params;
            this.mod = mod;
        }
    }

    /** Result of a write. inhibition is the camera's reason code when it refused, otherwise 0. */
    public static final class Result {
        public final boolean ok;
        public final int inhibition;
        public final String error;

        Result(boolean ok, int inhibition, String error) {
            this.ok = ok;
            this.inhibition = inhibition;
            this.error = error;
        }
    }

    public interface Listener {
        /** Parameters changed, by the app or by the camera (mode dial, buttons, ...). */
        void onParamsChanged();

        /** Shutter speed or aperture changed. */
        void onExposureChanged();

        /**
         * Result of the camera's autofocus.
         * @param status one of CameraEx.AutoFocusDoneListener.STATUS_*
         * @param indices indices of the focused frames (see {@link FocusFrame#index})
         */
        void onFocus(int status, int[] indices);

        /** Result of an AF run started by a half press (also in AF-C, where no frames are reported). */
        void onFocusResult(boolean success);

        /** An exposure was made (status 0) */
        void onShot();

        /** A second of the self-timer counted by the app has passed; remaining seconds */
        void onTimerTick(int remaining);

        /** New live view histogram (at most a few times per second) */
        void onHistogram();

        /** The focus ring started (true) or stopped (false) turning */
        void onFocusRing(boolean turning);

        /** The camera switched the magnifier off (or changed its level) by itself */
        void onMagnificationChanged(boolean on);

        /** The camera reports a stored picture file (platform V6 and newer only) */
        void onStored(String file);
    }

    /** An AF frame in screen coordinates of the overlay. */
    public static final class FocusFrame {
        public final int index;
        public final Rect rect;
        public final boolean enabled;

        FocusFrame(int index, Rect rect, boolean enabled) {
            this.index = index;
            this.rect = rect;
            this.enabled = enabled;
        }
    }

    /** Exposure modes as reported in the scene mode parameter */
    public enum ExposureMode {
        P("P"), A("A"), S("S"), M("M"), AUTO("AUTO"), OTHER("");

        public final String letter;

        ExposureMode(String letter) {
            this.letter = letter;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private CameraEx cameraEx;
    private Camera camera;
    private DisplayManager displayManager;
    /** Built-in flash popped up / external flash attached, as reported by the equipment callback */
    private volatile boolean internalFlash;
    private volatile boolean externalFlash;
    /** Width of a screen pixel relative to its height (1.33 on a 16:9 panel driven with 640x480) */
    private float panelRatio = 0f;
    private boolean directShutter;
    private List<FocusFrame> focusFrames = new ArrayList<FocusFrame>();
    private String focusFramesKey;
    private Params current;
    private boolean previewRunning;

    // Exposure values as reported by the camera
    private int shutterN, shutterD;
    private int aperture;

    public CameraSession(Listener listener) {
        this.listener = listener;
    }

    public boolean isOpen() {
        return cameraEx != null;
    }

    private final LiveView liveView = new LiveView();

    private java.util.Map<String, String> probe;

    /** What image processing the camera offers (asked once, nothing is taken) */
    public synchronized java.util.Map<String, String> hardwareProbe() {
        if (probe == null && cameraEx != null) {
            probe = HardwareProbe.run(cameraEx);
            liveFrame();
            probe.put("SplitExposure", liveView.splitExposure());
            probe.put("Dekoder", RawCore.STATUS);
            Log.info("hardware probe " + probe);
        }
        return probe;
    }

    /** One JPEG frame of the live view (for the browser), or null. Callable from any thread. */
    public byte[] liveFrame() {
        if (rawLab != null && rawLab.running()) {
            // The RAW experiment owns the camera sequence
            return null;
        }
        return liveView.frame(cameraEx);
    }

    private volatile RawLab rawLab;

    /** Experiments with the RAW pipeline (stage 3c); call on the main thread */
    public RawLab rawLab() {
        if (rawLab == null) {
            rawLab = new RawLab(new RawLab.Host() {
                @Override
                public CameraEx cameraEx() {
                    return cameraEx;
                }

                @Override
                public void stopLiveView() {
                    liveView.stop();
                }

                @Override
                public boolean captureSingle() {
                    return CameraSession.this.captureSingle();
                }

                @Override
                public boolean isCapturing() {
                    return capturing;
                }

                @Override
                public int evIndex() {
                    try {
                        return current.params.getExposureCompensation();
                    } catch (Throwable t) {
                        return 0;
                    }
                }

                @Override
                public float evStep() {
                    try {
                        return current.params.getExposureCompensationStep();
                    } catch (Throwable t) {
                        return 1f / 3;
                    }
                }

                @Override
                public boolean setEvIndex(int index) {
                    return Settings.get("ev").write(CameraSession.this, String.valueOf(index)).ok;
                }
            });
        }
        return rawLab;
    }

    public void open() {
        if (cameraEx != null) {
            return;
        }
        Log.info("open camera");
        cameraEx = CameraEx.open(0, null);
        camera = cameraEx.getNormalCamera();
        try {
            displayManager = new DisplayManager();
            readPixelAspect();
        } catch (Throwable t) {
            Log.error("DisplayManager", t);
        }
        refresh();
        registerListeners();
        if (histogramWanted) {
            setHistogramEnabled(true);
        }
        handler.postDelayed(poll, 1500);
    }

    // ---- live view histogram (computed by the camera's image processor) ----

    private boolean histogramWanted;
    private volatile short[] histY;
    private volatile short[] histR;
    private volatile short[] histG;
    private volatile short[] histB;
    private long histShown;
    private int histCount;

    public void setHistogramEnabled(boolean on) {
        histogramWanted = on;
        if (cameraEx == null) {
            return;
        }
        try {
            if (on) {
                cameraEx.setPreviewAnalizeListener(new CameraEx.PreviewAnalizeListener() {
                    @Override
                    public void onAnalizedData(CameraEx.AnalizedData data, CameraEx c) {
                        if (data == null || data.hist == null) {
                            return;
                        }
                        histY = data.hist.Y;
                        histR = data.hist.R;
                        histG = data.hist.G;
                        histB = data.hist.B;
                        if (histCount++ == 0) {
                            Log.info("histogram: " + (histY != null ? histY.length : 0) + " bins, rgb="
                                    + (histR != null ? histR.length : 0));
                        }
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - histShown >= 250) {
                            histShown = now;
                            listener.onHistogram();
                        }
                    }
                });
                Log.info("histogram on");
            } else {
                cameraEx.setPreviewAnalizeListener(null);
                histY = histR = histG = histB = null;
            }
        } catch (Throwable t) {
            Log.error("setPreviewAnalizeListener", t);
        }
    }

    /** The camera delivers no colour histograms (ILCE-6000): the app computes them itself */
    public boolean histogramRgbMissing() {
        return histR == null || histG == null || histB == null;
    }

    /** Y, R, G, B bins of the live view, or null while none arrived */
    public short[][] histogram() {
        short[] y = histY;
        if (y == null) {
            return null;
        }
        return new short[][]{y, histR, histG, histB};
    }

    // ---- focus magnifier ----

    /** Index in the camera's magnification list, -1 while off */
    private int magIndex = -1;
    private int magX, magY;
    private String magLabel;

    private java.util.List<Integer> magnifications() {
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        try {
            for (Object o : current.mod.getSupportedPreviewMagnification()) {
                out.add(((Number) o).intValue());
            }
        } catch (Throwable t) {
            String raw = current != null ? current.params.get("prv-mag-values") : null;
            if (raw != null) {
                for (String v : raw.split(",")) {
                    try {
                        out.add(Integer.parseInt(v.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return out;
    }

    private boolean magProbed;

    /** Asks the camera once which levels give which magnification, to find more steps (e.g. x3) */
    private void probeMagnification() {
        if (magProbed || cameraEx == null) {
            return;
        }
        magProbed = true;
        StringBuilder b = new StringBuilder("magnifier probe:");
        for (int level = 10; level <= 400; level += 10) {
            try {
                int r = cameraEx.getPreviewMagnificationRatio(level);
                if (r > 0) {
                    b.append(' ').append(level).append('=').append(r);
                }
            } catch (Throwable t) {
                b.append(' ').append(level).append("=err");
            }
        }
        Log.info(b.toString());
    }

    /** Next magnification level; false when the magnifier is off afterwards (or not available) */
    public boolean nextMagnification() {
        probeMagnification();
        java.util.List<Integer> levels = magnifications();
        if (cameraEx == null || levels.isEmpty()) {
            Log.info("magnifier: no levels");
            return false;
        }
        if (magIndex < 0) {
            // Start where the AF spot is, otherwise in the middle
            int[] fp = focusPoint();
            boolean flex = false;
            try {
                flex = Settings.isFlexSpot(current);
            } catch (Throwable ignored) {
            }
            magX = flex && fp != null ? fp[0] : 0;
            magY = flex && fp != null ? fp[1] : 0;
        }
        magIndex++;
        if (magIndex >= levels.size()) {
            stopMagnification();
            return false;
        }
        return applyMagnification(levels.get(magIndex));
    }

    private boolean applyMagnification(int level) {
        try {
            cameraEx.setPreviewMagnification(level, new android.util.Pair<Integer, Integer>(magX, magY));
            int ratio = 0;
            try {
                ratio = cameraEx.getPreviewMagnificationRatio(level);
            } catch (Throwable ignored) {
            }
            magLabel = ratio > 0 ? String.format(java.util.Locale.US, "×%.1f", ratio / 100.0).replace('.', ',') : "×" + (magIndex + 1);
            Log.info("magnifier level=" + level + " ratio=" + ratio + " at " + magX + "," + magY);
            return true;
        } catch (Throwable t) {
            Log.error("setPreviewMagnification", t);
            magIndex = -1;
            return false;
        }
    }

    /** Moves the magnified area by a tenth of the picture; (0, 0) returns to the middle */
    public void moveMagnification(int dx, int dy) {
        java.util.List<Integer> levels = magnifications();
        if (magIndex < 0 || magIndex >= levels.size()) {
            return;
        }
        if (dx == 0 && dy == 0) {
            magX = 0;
            magY = 0;
        } else {
            magX = Math.max(-900, Math.min(900, magX + dx * 100));
            magY = Math.max(-900, Math.min(900, magY + dy * 100));
        }
        applyMagnification(levels.get(magIndex));
    }

    public void stopMagnification() {
        if (magIndex < 0 || cameraEx == null) {
            magIndex = -1;
            return;
        }
        magIndex = -1;
        try {
            cameraEx.stopPreviewMagnification();
            Log.info("magnifier off");
        } catch (Throwable t) {
            Log.error("stopPreviewMagnification", t);
        }
    }

    public boolean isMagnifying() {
        return magIndex >= 0;
    }

    /** "×5,9" while the magnifier is on, otherwise null */
    public String magnificationLabel() {
        return magIndex >= 0 ? magLabel : null;
    }

    /** Position of the magnified area in the -1000..1000 system */
    public int[] magnificationPoint() {
        return magIndex >= 0 ? new int[]{magX, magY} : null;
    }

    /** Focal length of the lens in mm, 0 if unknown */
    public float focalLength() {
        // The camera reports it through the FocalLengthChangeListener; the parameter stays 0
        if (focalRaw > 0) {
            return focalRaw / focalScale();
        }
        CameraEx.LensInfo l = lens();
        if (l != null && l.FocalLength != null && l.FocalLength.wide > 0) {
            return l.FocalLength.wide / focalScale();
        }
        try {
            return current != null ? current.params.getFocalLength() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- lens ----

    private volatile int focalRaw = -1;
    private CameraEx.LensInfo lensInfo;
    private boolean lensRead;

    /** Lens data from the camera (name, focal range, aperture range), cached until the lens changes */
    public CameraEx.LensInfo lens() {
        if (!lensRead && cameraEx != null) {
            lensRead = true;
            try {
                lensInfo = cameraEx.getLensInfo();
                if (lensInfo != null) {
                    Log.info("lens " + lensInfo.LensName + " type=" + lensInfo.LensType
                            + " focal=" + (lensInfo.FocalLength != null ? lensInfo.FocalLength.wide + "-" + lensInfo.FocalLength.tele : "?")
                            + " F=" + (lensInfo.MinFValue != null ? lensInfo.MinFValue.wide + "/" + lensInfo.MinFValue.tele : "?"));
                }
            } catch (Throwable t) {
                Log.error("getLensInfo", t);
            }
        }
        return lensInfo;
    }

    /**
     * Units of the focal length are not documented: compare the reported shortest focal length
     * with the number in the lens name ("FE 24-105mm F4" -> 24) to find the factor (1, 10 or 100).
     */
    private float focalScale() {
        CameraEx.LensInfo l = lens();
        if (l == null || l.FocalLength == null || l.FocalLength.wide <= 0 || l.LensName == null) {
            return 1f;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(?:-\\d+)?\\s*mm").matcher(l.LensName);
        if (m.find()) {
            float named = Float.parseFloat(m.group(1));
            if (named > 0) {
                float ratio = l.FocalLength.wide / named;
                if (ratio > 50) {
                    return 100f;
                }
                if (ratio > 5) {
                    return 10f;
                }
            }
        }
        return 1f;
    }

    /** Lens keys that sent events since the lens was attached (focus hold, zoom lever, aperture ring) */
    private final java.util.Set<String> lensKeysSeen = new java.util.LinkedHashSet<String>();

    public void lensKeySeen(String name) {
        if (lensKeysSeen.add(name)) {
            Log.info("lens key " + name);
        }
    }

    /** Rows for the lens page: label and value */
    public java.util.List<String[]> lensDetails() {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        CameraEx.LensInfo l = lens();
        if (l == null || l.LensName == null || l.LensName.trim().length() == 0) {
            out.add(new String[]{"Objektiv", "keine Daten (Adapter oder manuelles Objektiv)"});
            return out;
        }
        String name = l.LensName.trim();
        float scale = focalScale();
        out.add(new String[]{"Name", name});
        out.add(new String[]{"Anschluss", "E-mount".equals(l.LensType) ? "E-Mount" : "A-mount".equals(l.LensType) ? "A-Mount (Adapter)" : String.valueOf(l.LensType)});
        if (l.FocalLength != null && l.FocalLength.wide > 0) {
            int w = Math.round(l.FocalLength.wide / scale), t = Math.round(l.FocalLength.tele / scale);
            out.add(new String[]{"Brennweite", w == t ? w + " mm (Festbrennweite)" : w + "–" + t + " mm"});
            // APS-C: equivalent focal length on full frame
            out.add(new String[]{"Kleinbild-Äquivalent", w == t ? Math.round(w * 1.5f) + " mm" : Math.round(w * 1.5f) + "–" + Math.round(t * 1.5f) + " mm"});
        }
        float f = focalLength();
        if (f > 0) {
            out.add(new String[]{"Aktuelle Brennweite", Math.round(f) + " mm"});
        }
        if (l.MinFValue != null && l.MinFValue.wide > 0) {
            out.add(new String[]{"Lichtstärke", fRange(l.MinFValue)});
        }
        try {
            if (l.MaxFValue != null && l.MaxFValue.wide > 0) {
                out.add(new String[]{"Kleinste Blende", fRange(l.MaxFValue)});
            }
        } catch (Throwable ignored) {
        }
        // Fields below are not known by every firmware (NoSuchFieldError on the ILCE-6000)
        try {
            if (l.FixedAperture) {
                out.add(new String[]{"Blende", "fest"});
            }
        } catch (Throwable ignored) {
        }
        String n = " " + name.toUpperCase(java.util.Locale.US) + " ";
        out.add(new String[]{"Bildstabilisator", n.contains("OSS") ? "Ja (OSS im Objektiv)" : "nicht angegeben"});
        out.add(new String[]{"Motorzoom", n.contains(" PZ ") ? "Ja (Power Zoom)" : "Nein"});
        String series = n.contains(" GM ") ? "G Master" : n.contains(" ZA ") ? "Zeiss" : n.contains(" G ") ? "G" : null;
        if (series != null) {
            out.add(new String[]{"Baureihe", series});
        }
        try {
            if (l.PhaseShiftSensor != null) {
                out.add(new String[]{"Phasen-AF", String.valueOf(l.PhaseShiftSensor)});
            }
        } catch (Throwable ignored) {
        }
        try {
            out.add(new String[]{"Objektiv-Firmware", String.valueOf(l.LensVersion)});
        } catch (Throwable ignored) {
        }
        out.add(new String[]{"Tasten am Objektiv", lensKeysSeen.isEmpty() ? "noch keine Taste gemeldet" : android.text.TextUtils.join(", ", lensKeysSeen)});
        return out;
    }

    /** f-number × 10 as "F2,8" or "F22" (no ",0" for whole numbers) */
    private static String fNumber(int tenths) {
        return tenths % 10 == 0 ? "F" + tenths / 10 : "F" + tenths / 10 + "," + tenths % 10;
    }

    private String fRange(CameraEx.WideTele v) {
        // Aperture values come as f-number × 10
        String w = fNumber(v.wide);
        String t = fNumber(v.tele);
        return v.wide == v.tele ? w : w + "–" + t.substring(1);
    }

    /** Lens data for the metadata of files the app writes, or null without lens data */
    public TiffOut.Lens lensForFiles() {
        try {
            CameraEx.LensInfo l = lens();
            if (l == null || l.LensName == null || l.LensName.trim().length() == 0) {
                return null;
            }
            TiffOut.Lens o = new TiffOut.Lens();
            o.name = l.LensName.trim();
            float scale = focalScale();
            if (l.FocalLength != null && l.FocalLength.wide > 0) {
                o.focalMin = l.FocalLength.wide / scale;
                o.focalMax = l.FocalLength.tele / scale;
            }
            if (l.MinFValue != null && l.MinFValue.wide > 0) {
                o.fMin = l.MinFValue.wide / 10.0;
                o.fMax = l.MinFValue.tele / 10.0;
            }
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** "FE 24-105mm F4 G OSS · 50 mm" or null without lens data */
    public String lensText() {
        CameraEx.LensInfo l = lens();
        if (l == null || l.LensName == null || l.LensName.length() == 0) {
            return null;
        }
        float f = focalLength();
        return l.LensName.trim() + (f > 0 ? " · " + Math.round(f) + " mm" : "");
    }

    /** Shutter speed in seconds, 0 if unknown or BULB */
    public double shutterSeconds() {
        return shutterN > 0 && shutterD > 0 && !isBulb() ? (double) shutterN / shutterD : 0;
    }

    /** BULB is reported as shutter speed 65535/1 (as in Sony's own apps) */
    public boolean isBulb() {
        return shutterN == 65535 && shutterD == 1;
    }

    /** A picture is being taken (or the self-timer runs) */
    public boolean isCapturing() {
        return capturing;
    }

    /** One picture without self-timer, for sequences run by the app */
    public boolean captureSingle() {
        if (cameraEx == null || capturing) {
            return false;
        }
        capturing = true;
        burstCapture = false;
        try {
            cameraEx.burstableTakePicture();
            Log.info("sequence: burstableTakePicture");
            return true;
        } catch (Throwable t) {
            capturing = false;
            Log.error("burstableTakePicture", t);
            return false;
        }
    }

    /** Opens the shutter in BULB; it stays open until {@link #stopBulb()} */
    public boolean startBulb() {
        if (cameraEx == null || capturing || !isBulb()) {
            return false;
        }
        capturing = true;
        burstCapture = true;
        try {
            cameraEx.burstableTakePicture();
            Log.info("bulb open");
            return true;
        } catch (Throwable t) {
            capturing = false;
            Log.error("bulb", t);
            return false;
        }
    }

    public void stopBulb() {
        if (cameraEx != null && capturing) {
            Log.info("bulb close");
            endCapture();
        }
    }

    /**
     * Drives the focus by one small step towards infinity (far) or the near limit, like the MF
     * control of Sony's Smart Remote app (startOneShotFocusDrive with speed 4). The position is
     * reported through the FocusDriveListener. shiftFocusPosition blocks the a6000 and is not used.
     */
    public interface ShiftResult {
        void done(Boolean ok);
    }

    /** Last focus position reported by the camera, -1 while unknown */
    private volatile int focusPos = -1;
    private volatile int focusMax = -1;

    /** The camera can drive the focus for apps (not the a6000: focus-drive-supported=false) */
    public boolean focusDriveSupported() {
        try {
            return current != null && current.mod.isFocusDriveSupported() && current.mod.getMaxFocusDriveSpeed() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public int focusPosition() {
        return focusPos;
    }

    public int focusMaxPosition() {
        return focusMax;
    }

    public void driveFocus(final int steps, final boolean far, final ShiftResult result) {
        final int[] left = {steps};
        final int before = focusPos;
        final Runnable[] step = new Runnable[1];
        step[0] = new Runnable() {
            @Override
            public void run() {
                if (left[0] <= 0) {
                    // Give the lens a moment, then report whether the position moved
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            Log.info("focus drive " + steps + (far ? " far" : " near") + ": " + before + " -> " + focusPos + " / " + focusMax);
                            boolean atEnd = focusMax > 0 && focusPos >= focusMax;
                            result.done(!atEnd);
                        }
                    }, 150);
                    return;
                }
                left[0]--;
                try {
                    cameraEx.startOneShotFocusDrive(far ? CameraEx.FOCUS_DRIVE_DIRECTION_FAR : CameraEx.FOCUS_DRIVE_DIRECTION_NEAR, 4);
                } catch (Throwable t) {
                    Log.error("startOneShotFocusDrive", t);
                    result.done(null);
                    return;
                }
                handler.postDelayed(this, 80);
            }
        };
        handler.post(step[0]);
    }

    private void registerListeners() {
        try {
            cameraEx.setFocalLengthChangeListener(new CameraEx.FocalLengthChangeListener() {
                @Override
                public void onFocalLengthChanged(int length, CameraEx ex) {
                    if (focalRaw != length) {
                        Log.info("focal length raw=" + length);
                    }
                    focalRaw = length;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onExposureChanged();
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Log.error("setFocalLengthChangeListener", t);
        }
        try {
            cameraEx.setFocusDriveListener(new CameraEx.FocusDriveListener() {
                @Override
                public void onChanged(CameraEx.FocusPosition p, CameraEx ex) {
                    if (p != null) {
                        focusPos = p.currentPosition;
                        focusMax = p.maxPosition;
                    }
                }
            });
        } catch (Throwable t) {
            Log.error("setFocusDriveListener", t);
        }
        try {
            // Reports the magnifier state and the turning of the focus ring (Sony's MF assist uses it)
            cameraEx.setPreviewMagnificationListener(new CameraEx.PreviewMagnificationListener() {
                @Override
                public void onChanged(final boolean on, int mag, int level, android.util.Pair coordinates, CameraEx ex) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            Log.info("magnifier changed on=" + on);
                            if (!on) {
                                magIndex = -1;
                            }
                            listener.onMagnificationChanged(on);
                        }
                    });
                }

                @Override
                public void onInfoUpdated(final boolean focusRing, android.util.Pair focusPos, CameraEx ex) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onFocusRing(focusRing);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Log.error("setPreviewMagnificationListener", t);
        }
        try {
            cameraEx.setSettingChangedListener(new CameraEx.SettingChangedListener() {
                @Override
                public void onChanged(int[] types, Camera.Parameters params, CameraEx ex) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                            listener.onParamsChanged();
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Log.error("setSettingChangedListener", t);
        }
        try {
            cameraEx.setShutterListener(new CameraEx.ShutterListener() {
                @Override
                public void onShutter(final int status, CameraEx ex) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            CameraSession.this.onShutter(status);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Log.error("setShutterListener", t);
        }
        try {
            cameraEx.setStoreImageCompleteListener(new CameraEx.StoreImageCompleteListener() {
                @Override
                public void onDone(int status, final CameraEx.StoreImageInfo info, CameraEx ex) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onStored(info != null ? info.DirectoryName + "/" + info.FileName : "?");
                        }
                    });
                }
            });
            Log.info("store image listener available");
        } catch (Throwable t) {
            // Not available before platform V6 (e.g. ILCE-6000); the write monitor watches the card instead
            Log.info("store image listener not available: " + t.getClass().getSimpleName());
        }
        try {
            cameraEx.setEquipmentCallback(new CameraEx.EquipmentCallback() {
                @Override
                public void onEquipmentChange(final int type, final int status, CameraEx ex) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            Log.info("equipment type=" + type + " status=" + status);
                            // A lens change also arrives here: read the lens data again
                            lensRead = false;
                            focalRaw = -1;
                            lensKeysSeen.clear();
                            if (type == CameraEx.EquipmentCallback.DEVICE_TYPE_INTERNAL_FLASH) {
                                internalFlash = status == CameraEx.EquipmentCallback.DEVICE_AVAILABLE;
                            } else if (type == CameraEx.EquipmentCallback.DEVICE_TYPE_EXTERNAL_FLASH) {
                                externalFlash = status == CameraEx.EquipmentCallback.DEVICE_AVAILABLE;
                            }
                            Settings.flashReady = flashReady();
                            refresh();
                            invalidateFocusFrames();
                            listener.onParamsChanged();
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Log.error("setEquipmentCallback", t);
        }
        try {
            cameraEx.setFlashChargingStateListener(new CameraEx.FlashChargingStateListener() {
                @Override
                public void onChanged(final int state, CameraEx ex) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            Log.info("flash charging state=" + state);
                            refresh();
                            listener.onParamsChanged();
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Log.error("setFlashChargingStateListener", t);
        }
        try {
            cameraEx.setAutoFocusDoneListener(new CameraEx.AutoFocusDoneListener() {
                @Override
                public void onDone(final int status, final int[] indices, CameraEx ex) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onFocus(status, indices != null ? indices : new int[0]);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Log.error("setAutoFocusDoneListener", t);
        }
        try {
            cameraEx.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
                @Override
                public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo info, CameraEx ex) {
                    shutterN = info.currentShutterSpeed_n;
                    shutterD = info.currentShutterSpeed_d;
                    postExposure();
                }
            });
        } catch (Throwable t) {
            Log.error("setShutterSpeedChangeListener", t);
        }
        try {
            cameraEx.setApertureChangeListener(new CameraEx.ApertureChangeListener() {
                @Override
                public void onApertureChange(CameraEx.ApertureInfo info, CameraEx ex) {
                    aperture = info.currentAperture;
                    postExposure();
                }
            });
        } catch (Throwable t) {
            Log.error("setApertureChangeListener", t);
        }
    }

    /**
     * Reads the parameters regularly. Not every change (e.g. the built-in flash popping up)
     * is reported by a listener on every model.
     */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (cameraEx == null) {
                return;
            }
            String before = signature();
            refresh();
            if (!before.equals(signature())) {
                invalidateFocusFrames();
                listener.onParamsChanged();
            }
            liveView.stopIfIdle();
            handler.postDelayed(this, 1500);
        }
    };

    private String signature() {
        try {
            return current != null ? current.params.flatten() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** All parameters as the camera reports them (key=value;...), for research. */
    public String flatten() {
        return signature();
    }

    private void postExposure() {
        post(new Runnable() {
            @Override
            public void run() {
                listener.onExposureChanged();
            }
        });
    }

    private void post(Runnable r) {
        handler.post(r);
    }

    /**
     * The panel's aspect ratio. The ILCE-6000 has a 16:9 panel, while Android draws 640x480 pixels,
     * so every overlay pixel is a third wider than high.
     */
    private void readPixelAspect() {
        try {
            DisplayManager.DeviceInfo info = displayManager.getDeviceInfo(displayManager.getActiveDevice());
            if (info != null && info.res_w > 0 && info.res_h > 0) {
                float panel;
                switch (info.aspect) {
                    case 2: panel = 16f / 9f; break;  // ASPECT_RATIO_16_9
                    case 1: panel = 3f / 2f; break;   // ASPECT_RATIO_3_2
                    case 3: panel = 4f / 3f; break;   // ASPECT_RATIO_4_3
                    case 4: panel = 5f / 3f; break;   // ASPECT_RATIO_5_3
                    case 5: panel = 11f / 9f; break;  // ASPECT_RATIO_11_9
                    default: panel = 0;
                }
                panelRatio = panel;
                Log.info("display " + info.res_w + "x" + info.res_h + " aspect=" + info.aspect + " panel=" + panel);
            }
        } catch (Throwable t) {
            Log.error("display info", t);
        }
    }

    /**
     * Width of an overlay pixel relative to its height. The panel's aspect ratio is reported by
     * the camera, the overlay is drawn with the size of the Android view (640x480 on the ILCE-6000).
     */
    public float pixelAspect(int viewWidth, int viewHeight) {
        if (panelRatio <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return 1f;
        }
        return panelRatio / ((float) viewWidth / viewHeight);
    }

    public boolean flashReady() {
        return internalFlash || externalFlash;
    }

    /**
     * Starts the live view on the given surface.
     * @param direct true: the camera handles the shutter button by itself (no AF feedback to the app);
     *               false: the app starts AF and capture itself
     */
    public void startPreview(SurfaceHolder holder, boolean direct) {
        directShutter = direct;
        if (camera == null || previewRunning) {
            return;
        }
        try {
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            previewRunning = true;
            Log.info("preview started");
        } catch (Throwable t) {
            Log.error("startPreview", t);
            return;
        }
        if (!direct) {
            return;
        }
        try {
            // The camera then handles half press (AF) and full press (capture) by itself
            cameraEx.startDirectShutter();
            Log.info("direct shutter started");
        } catch (Throwable t) {
            Log.error("startDirectShutter", t);
        }
    }

    public boolean isDirectShutter() {
        return directShutter;
    }

    private boolean focusing;
    private boolean capturing;

    /** Half press: start the autofocus. The result arrives through the AF listener. */
    public void startFocus() {
        if (camera == null || focusing || capturing) {
            return;
        }
        focusing = true;
        try {
            camera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(final boolean success, Camera c) {
                    Log.info("autoFocus done success=" + success);
                    post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onFocusResult(success);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            focusing = false;
            Log.error("autoFocus", t);
        }
    }

    /** Half press released */
    public void cancelFocus() {
        if (camera == null || !focusing || capturing) {
            return;
        }
        focusing = false;
        try {
            camera.cancelAutoFocus();
        } catch (Throwable t) {
            Log.error("cancelAutoFocus", t);
        }
    }

    /** Full press: take a picture (or start a burst while the button is held). */
    public void capture(boolean burst) {
        if (cameraEx == null || capturing) {
            return;
        }
        capturing = true;
        // In BULB the shutter stays open while the button is held, like a burst
        burstCapture = burst || isBulb();
        int appTimer = appTimerSeconds();
        if (appTimer > 0) {
            // The app counts down and then releases like a normal single shot
            burstCapture = false;
            selfTimerEnd = android.os.SystemClock.uptimeMillis() + appTimer * 1000L;
            appTimerRunning = true;
            handler.post(appTimerTick);
            Log.info("app self-timer " + appTimer + " s");
            return;
        }
        int timer = selfTimerSeconds();
        if (timer > 0) {
            // The camera counts down itself (lamp and beeps) and reports the exposure via onShutter
            burstCapture = false;
            try {
                cameraEx.startSelfTimerShutter();
                selfTimerEnd = android.os.SystemClock.uptimeMillis() + timer * 1000L;
                Log.info("startSelfTimerShutter " + timer + " s");
            } catch (Throwable t) {
                capturing = false;
                selfTimerEnd = 0;
                Log.error("startSelfTimerShutter", t);
            }
            return;
        }
        try {
            cameraEx.burstableTakePicture();
            Log.info("burstableTakePicture");
        } catch (Throwable t) {
            capturing = false;
            Log.error("burstableTakePicture", t);
        }
    }

    /** Full press released: ends a burst */
    public void stopCapture() {
        if (cameraEx == null || !capturing || !burstCapture) {
            return;
        }
        endCapture();
    }

    private boolean burstCapture;
    /** Uptime when the running self-timer fires, 0 if none runs */
    private long selfTimerEnd;

    private boolean appTimerRunning;

    private int appTimerSeconds() {
        try {
            if (current != null && "single".equals(current.mod.getDriveMode()) && current.mod.getSelfTimer() == 0) {
                return Settings.appTimer;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private final Runnable appTimerTick = new Runnable() {
        @Override
        public void run() {
            if (selfTimerEnd == 0) {
                return;
            }
            long left = selfTimerEnd - android.os.SystemClock.uptimeMillis();
            if (left <= 0) {
                selfTimerEnd = 0;
                appTimerRunning = false;
                try {
                    cameraEx.burstableTakePicture();
                    Log.info("app self-timer: burstableTakePicture");
                } catch (Throwable t) {
                    capturing = false;
                    Log.error("burstableTakePicture", t);
                }
                return;
            }
            listener.onTimerTick((int) ((left + 999) / 1000));
            // Faster beeps in the last two seconds, like the camera
            long next = left > 2000 ? (left - 1) % 1000 + 1 : Math.min(left, 250);
            handler.postDelayed(this, next);
        }
    };

    private int selfTimerSeconds() {
        try {
            if (current != null && "single".equals(current.mod.getDriveMode())) {
                return current.mod.getSelfTimer();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    public boolean selfTimerRunning() {
        return selfTimerEnd != 0;
    }

    /** Seconds until the self-timer fires (rounded up), 0 if none runs */
    public int selfTimerRemaining() {
        if (selfTimerEnd == 0) {
            return 0;
        }
        long ms = selfTimerEnd - android.os.SystemClock.uptimeMillis();
        return ms <= 0 ? 0 : (int) ((ms + 999) / 1000);
    }

    /** Stops a running self-timer, like pressing a key during the countdown on the camera */
    public void cancelSelfTimer() {
        if (selfTimerEnd == 0) {
            return;
        }
        selfTimerEnd = 0;
        handler.removeCallbacks(appTimerTick);
        if (appTimerRunning) {
            appTimerRunning = false;
            capturing = false;
            Log.info("app self-timer cancelled");
            return;
        }
        try {
            cameraEx.cancelTakePicture();
            cameraEx.cancelSelfTimerShutter();
        } catch (Throwable t) {
            Log.error("cancelSelfTimerShutter", t);
        }
        capturing = false;
        focusing = false;
        Log.info("self-timer cancelled");
    }

    /**
     * Called by the camera after each exposure. burstableTakePicture keeps the capture
     * session open; for a single shot it has to be ended with cancelTakePicture, otherwise
     * the next burstableTakePicture fails (as the camera's own apps do it).
     */
    private void onShutter(int status) {
        Log.info("shutter status=" + status + (burstCapture ? " (burst)" : "") + (selfTimerEnd != 0 ? " (self-timer)" : ""));
        selfTimerEnd = 0;
        if (status == 0) {
            listener.onShot();
        }
        if (!burstCapture) {
            endCapture();
        }
    }

    private void endCapture() {
        try {
            cameraEx.cancelTakePicture();
        } catch (Throwable t) {
            Log.error("cancelTakePicture", t);
        }
        capturing = false;
        focusing = false;
    }

    public void close() {
        if (cameraEx == null) {
            return;
        }
        Log.info("close camera");
        handler.removeCallbacks(poll);
        liveView.stop();
        try {
            if (previewRunning) {
                camera.stopPreview();
            }
        } catch (Throwable t) {
            Log.error("stopPreview", t);
        }
        try {
            cameraEx.release();
        } catch (Throwable t) {
            Log.error("release", t);
        }
        if (displayManager != null) {
            try {
                displayManager.finish();
            } catch (Throwable t) {
                Log.error("DisplayManager.finish", t);
            }
            displayManager = null;
        }
        previewRunning = false;
        focusing = false;
        capturing = false;
        focusFrames = new ArrayList<FocusFrame>();
        focusFramesKey = null;
        cameraEx = null;
        camera = null;
        current = null;
    }

    /** Reads the parameters from the camera. */
    public void refresh() {
        if (camera == null) {
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            current = new Params(p, cameraEx.createParametersModifier(p));
            try {
                android.util.Pair<Integer, Integer> ss = current.mod.getShutterSpeed();
                if (ss != null) {
                    shutterN = ss.first;
                    shutterD = ss.second;
                }
            } catch (Throwable ignored) {
            }
            try {
                aperture = current.mod.getAperture();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Log.error("getParameters", t);
        }
    }

    public Params current() {
        return current;
    }

    /** An empty parameter set: only values that are set on it are sent to the camera. */
    public Params createEdit() {
        Camera.Parameters p = cameraEx.createEmptyParameters();
        return new Params(p, cameraEx.createParametersModifier(p));
    }

    public Result apply(Params edit) {
        try {
            camera.setParameters(edit.params);
            refresh();
            return new Result(true, 0, null);
        } catch (Throwable t) {
            int reason = 0;
            try {
                reason = cameraEx.getInhibitionInfo();
            } catch (Throwable ignored) {
            }
            Log.error("setParameters refused, inhibition=" + reason, t);
            refresh();
            return new Result(false, reason, t.getClass().getSimpleName());
        }
    }

    public void adjustShutterSpeed(int steps) {
        try {
            cameraEx.adjustShutterSpeed(steps);
        } catch (Throwable t) {
            Log.error("adjustShutterSpeed", t);
        }
    }

    public void adjustAperture(int steps) {
        try {
            cameraEx.adjustAperture(steps);
        } catch (Throwable t) {
            Log.error("adjustAperture", t);
        }
    }

    public void adjustProgramShift(int steps) {
        try {
            cameraEx.adjustProgramLine(steps);
        } catch (Throwable t) {
            Log.error("adjustProgramLine", t);
        }
    }

    public ExposureMode exposureMode() {
        String scene = null;
        try {
            scene = current != null ? current.params.getSceneMode() : null;
        } catch (Throwable ignored) {
        }
        if (scene == null) {
            return ExposureMode.OTHER;
        }
        if (scene.equals("program-auto")) return ExposureMode.P;
        if (scene.equals("aperture-priority")) return ExposureMode.A;
        if (scene.equals("shutter-speed")) return ExposureMode.S;
        if (scene.equals("manual-exposure")) return ExposureMode.M;
        if (scene.equals("auto") || scene.startsWith("superior")) return ExposureMode.AUTO;
        return ExposureMode.OTHER;
    }

    /** Sets the exposure mode. In app mode the camera does not follow the mode dial by itself. */
    public Result setSceneMode(String mode) {
        try {
            List<String> supported = current != null ? current.params.getSupportedSceneModes() : null;
            if (supported != null && !supported.contains(mode)) {
                Log.info("scene mode " + mode + " not supported: " + supported);
                return new Result(false, 0, "nicht unterstützt");
            }
            Params edit = createEdit();
            edit.params.setSceneMode(mode);
            Result r = apply(edit);
            Log.info("scene mode " + mode + " -> " + (r.ok ? "ok" : "refused " + r.inhibition));
            return r;
        } catch (Throwable t) {
            Log.error("setSceneMode " + mode, t);
            return new Result(false, 0, t.getClass().getSimpleName());
        }
    }

    public String sceneMode() {
        try {
            return current != null ? current.params.getSceneMode() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * AF frames of the current focus area mode, converted to overlay coordinates.
     * The camera reports frames in a -1000..1000 system relative to the displayed video area.
     * Index 0 is the whole area, higher indices are the single frames.
     */
    public List<FocusFrame> focusFrames(int viewWidth, int viewHeight) {
        if (cameraEx == null || current == null) {
            return focusFrames;
        }
        String mode = null;
        String aspect = null;
        String spotSize = null;
        try {
            mode = current.mod.getFocusAreaMode();
            aspect = current.mod.getImageAspectRatio();
            spotSize = current.mod.getFocusAreaFlexibleSpotSize();
        } catch (Throwable ignored) {
        }
        int[] point = focusPoint();
        String key = mode + "|" + aspect + "|" + spotSize + "|" + viewWidth + "x" + viewHeight
                + (point != null ? "|" + point[0] + "," + point[1] : "");
        if (key.equals(focusFramesKey)) {
            return focusFrames;
        }
        focusFramesKey = key;
        List<FocusFrame> out = new ArrayList<FocusFrame>();
        flexRange = null;
        try {
            int asp = "16:9".equals(aspect) ? 1 : "3:2".equals(aspect) ? 0 : -1;
            CameraEx.FocusAreaInfos[] infos = cameraEx.getFocusAreaInfos(asp);
            Rect video = videoRect(viewWidth, viewHeight);
            boolean flex = mode != null && (mode.contains("flex") || mode.equals("local"));
            if (infos != null) {
                for (CameraEx.FocusAreaInfos info : infos) {
                    if (info == null || mode == null || !mode.equals(info.focusAreaMode) || info.rectInfos == null) {
                        continue;
                    }
                    int last = -1;
                    for (CameraEx.FocusAreaRectInfo r : info.rectInfos) {
                        if (r.enable && r.index > last) {
                            last = r.index;
                        }
                    }
                    for (CameraEx.FocusAreaRectInfo r : info.rectInfos) {
                        Rect scalar = r.rect;
                        if (flex && r.index == 1) {
                            flexRange = new Rect(scalar);
                        }
                        if (flex && r.index == last && point != null) {
                            // The spot follows the focus point; the reported rect gives its size
                            int hw = scalar.width() / 2;
                            int hh = scalar.height() / 2;
                            spotHalf = new int[]{hw, hh};
                            scalar = new Rect(point[0] - hw, point[1] - hh, point[0] + hw, point[1] + hh);
                        }
                        out.add(new FocusFrame(r.index, toView(video, scalar), r.enable));
                    }
                }
            }
            Log.info("focus frames mode=" + mode + " aspect=" + aspect + " count=" + out.size()
                    + (point != null ? " point=" + point[0] + "," + point[1] : "") + " range=" + flexRange);
        } catch (Throwable t) {
            Log.error("getFocusAreaInfos", t);
        }
        focusFrames = out;
        return out;
    }

    /** Range the flexible spot can move in, in the camera's -1000..1000 system; known after focusFrames() */
    private Rect flexRange;
    /** Half width and height of the spot in the same system */
    private int[] spotHalf;

    /** Centre of the flexible spot in the -1000..1000 system, or null */
    public int[] focusPoint() {
        try {
            if (current == null) {
                return null;
            }
            android.util.Pair<?, ?> fp = current.mod.getFocusPoint();
            if (fp == null) {
                return null;
            }
            return new int[]{((Number) fp.first).intValue(), ((Number) fp.second).intValue()};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Moves the flexible spot by whole steps. Like the camera, the range is divided into
     * 17 columns and 11 rows.
     */
    public Result moveFocusPoint(int dx, int dy) {
        int[] p = focusPoint();
        if (p == null) {
            return new Result(false, 0, "no focus point");
        }
        Rect outer = flexRange != null ? flexRange : new Rect(-1000, -1000, 1000, 1000);
        // The whole spot stays inside the range, so its centre moves in a smaller area
        int hw = spotHalf != null ? spotHalf[0] : 0;
        int hh = spotHalf != null ? spotHalf[1] : 0;
        Rect range = new Rect(outer.left + hw, outer.top + hh, outer.right - hw, outer.bottom - hh);
        if (range.width() <= 0 || range.height() <= 0) {
            range = outer;
        }
        int stepX = Math.max(1, outer.width() / 16);
        int stepY = Math.max(1, outer.height() / 10);
        int x = Math.max(range.left, Math.min(range.right, p[0] + dx * stepX));
        int y = Math.max(range.top, Math.min(range.bottom, p[1] + dy * stepY));
        if (x == p[0] && y == p[1]) {
            return new Result(true, 0, null);
        }
        return setFocusPoint(x, y);
    }

    public Result centerFocusPoint() {
        return setFocusPoint(0, 0);
    }

    /**
     * Centre lock-on AF ("Mittel-AF-Verriegelung") on or off: the camera follows the subject
     * that is in the middle when focusing starts. Returns a short text for the screen.
     */
    public String toggleCenterTracking() {
        try {
            Params cur = current();
            java.util.List<?> modes = cur.mod.getSupportedTrackingFocusModes();
            if (modes == null || !modes.contains(CameraEx.ParametersModifier.TRACKING_FOCUS_ON)) {
                return "Mittel-AF-Verriegelung gibt es hier nicht";
            }
            boolean on = CameraEx.ParametersModifier.TRACKING_FOCUS_ON.equals(cur.mod.getTrackingFocus());
            Params edit = createEdit();
            edit.mod.setTrackingFocus(on ? CameraEx.ParametersModifier.TRACKING_FOCUS_OFF
                    : CameraEx.ParametersModifier.TRACKING_FOCUS_ON);
            Result r = apply(edit);
            Log.info("tracking " + (on ? "off" : "on") + " -> " + (r.ok ? "ok" : "refused " + r.inhibition));
            if (!r.ok) {
                return "Mittel-AF-Verriegelung gerade nicht möglich";
            }
            return on ? "Mittel-AF-Verriegelung aus" : "Mittel-AF-Verriegelung: Motiv in die Mitte, Auslöser halb drücken";
        } catch (Throwable t) {
            Log.error("tracking", t);
            return "Mittel-AF-Verriegelung gibt es hier nicht";
        }
    }

    public Result setFocusPoint(int x, int y) {
        try {
            Params edit = createEdit();
            edit.mod.setFocusPoint(x, y);
            Result r = apply(edit);
            invalidateFocusFrames();
            return r;
        } catch (Throwable t) {
            Log.error("setFocusPoint", t);
            return new Result(false, 0, t.getClass().getSimpleName());
        }
    }

    /** Holds or releases the exposure (AEL key) */
    public Result setExposureLock(boolean locked) {
        try {
            Params edit = createEdit();
            edit.mod.setAutoExposureLock(locked ? "locked" : "unlocked");
            Result r = apply(edit);
            Log.info("AEL " + (locked ? "locked" : "unlocked") + " -> " + (r.ok ? "ok" : "refused " + r.inhibition));
            return r;
        } catch (Throwable t) {
            Log.error("setAutoExposureLock", t);
            return new Result(false, 0, t.getClass().getSimpleName());
        }
    }

    public String focusAreaMode() {
        try {
            return current != null ? current.mod.getFocusAreaMode() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Invalidates the cached AF frames, e.g. after the AF area moved. */
    public void invalidateFocusFrames() {
        focusFramesKey = null;
    }

    /** Area of the overlay that shows the picture (without black borders) */
    public Rect videoArea(int viewWidth, int viewHeight) {
        return videoRect(viewWidth, viewHeight);
    }

    private Rect videoRect(int viewWidth, int viewHeight) {
        try {
            if (displayManager != null) {
                DisplayManager.VideoRect v = displayManager.getDisplayedVideoRect();
                if (v != null && v.pxRight > v.pxLeft && v.pxBottom > v.pxTop) {
                    return new Rect(v.pxLeft, v.pxTop, v.pxRight, v.pxBottom);
                }
            }
        } catch (Throwable t) {
            Log.error("getDisplayedVideoRect", t);
        }
        return new Rect(0, 0, viewWidth, viewHeight);
    }

    private static Rect toView(Rect video, Rect r) {
        int w = video.width();
        int h = video.height();
        return new Rect(
                video.left + (r.left + 1000) * w / 2000,
                video.top + (r.top + 1000) * h / 2000,
                video.left + (r.right + 1000) * w / 2000,
                video.top + (r.bottom + 1000) * h / 2000);
    }

    // ---- display helpers ----

    /** Exposure time set in the app beyond 30" (seconds, 0 = plain BULB); taken with BULB */
    private volatile int longSecs;

    public int longSecs() {
        return isBulb() ? longSecs : 0;
    }

    public void setLongSecs(int secs) {
        longSecs = secs;
    }

    /** 40", 1'30", 2' */
    public static String longText(int secs) {
        if (secs < 60) {
            return secs + "\"";
        }
        return secs / 60 + "'" + (secs % 60 != 0 ? String.format(java.util.Locale.US, "%02d\"", secs % 60) : "");
    }

    public String shutterText() {
        if (isBulb()) {
            return longSecs > 0 ? longText(longSecs) : "BULB";
        }
        if (shutterN <= 0 || shutterD <= 0) {
            return "--";
        }
        if (shutterN == 1 && shutterD > 1) {
            return "1/" + shutterD;
        }
        double s = (double) shutterN / shutterD;
        if (s >= 1 && Math.abs(s - Math.round(s)) < 0.01) {
            return Math.round(s) + "\"";
        }
        return String.format(java.util.Locale.US, "%.1f\"", s);
    }

    public String apertureText() {
        if (aperture <= 0) {
            return "F--";
        }
        // The camera reports the f-number multiplied by 100
        return String.format(java.util.Locale.US, "F%.1f", aperture / 100.0);
    }
}
