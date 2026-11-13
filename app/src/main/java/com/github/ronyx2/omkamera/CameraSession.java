package com.github.ronyx2.omkamera;

import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;

import com.sony.scalar.hardware.CameraEx;

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
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private CameraEx cameraEx;
    private Camera camera;
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

    public void open() {
        if (cameraEx != null) {
            return;
        }
        Log.info("open camera");
        cameraEx = CameraEx.open(0, null);
        camera = cameraEx.getNormalCamera();
        refresh();
        registerListeners();
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

    /** Starts the live view on the given surface and lets the camera handle the shutter button. */
    public void startPreview(SurfaceHolder holder) {
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
        try {
            // The camera then handles half press (AF) and full press (capture) by itself
            cameraEx.startDirectShutter();
            Log.info("direct shutter started");
        } catch (Throwable t) {
            Log.error("startDirectShutter", t);
        }
    }

    public void close() {
        if (cameraEx == null) {
            return;
        }
        Log.info("close camera");
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
        previewRunning = false;
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
