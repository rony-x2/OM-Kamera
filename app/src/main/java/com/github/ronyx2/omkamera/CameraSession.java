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

    /** One JPEG frame of the live view (for the browser), or null. Callable from any thread. */
    public byte[] liveFrame() {
        return liveView.frame(cameraEx);
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
                            Log.info("histogram: " + (histY != null ? histY.length : 0) + " bins");
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

    /** Next magnification level; false when the magnifier is off afterwards (or not available) */
    public boolean nextMagnification() {
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
        try {
            return current != null ? current.params.getFocalLength() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Shutter speed in seconds, 0 if unknown */
    public double shutterSeconds() {
        return shutterN > 0 && shutterD > 0 ? (double) shutterN / shutterD : 0;
    }

    private void registerListeners() {
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
        burstCapture = burst;
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

    public String shutterText() {
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
