package com.github.ronyx2.omkamera;

import android.os.Handler;
import android.os.SystemClock;

import com.sony.scalar.graphics.OptimizedImage;
import com.sony.scalar.graphics.imagefilter.ImageFilter;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.hardware.CameraSequence;
import com.sony.scalar.hardware.DeviceMemory;

import java.lang.reflect.Field;
import java.util.Vector;

/**
 * Experiments with the camera's RAW pipeline (stage 3c): the shutter sequence callback hands
 * the RAW data of each shot to the app instead of developing and storing it. Two modes:
 * "durchreiche" develops one shot with the default filter and stores it; "addition" adds
 * several shots with the RAW addition filter and stores the result. Everything is logged; a
 * watchdog releases the sequence if no callback arrives.
 * Runs on the main thread (the sequence delivers its callbacks there).
 */
public class RawLab implements CameraSequence.ShutterSequenceCallback {

    public interface Host {
        CameraEx cameraEx();

        /** Stops the live view JPEG sequence so only one sequence is open */
        void stopLiveView();

        boolean captureSingle();

        boolean isCapturing();
    }

    private final Host host;
    private final Handler handler = new Handler();
    private CameraSequence seq;
    private String mode;
    private int count;
    private int received;
    private DeviceMemory acc;
    private long t0;
    private String result = "noch nicht gestartet";

    public RawLab(Host host) {
        this.host = host;
    }

    public String result() {
        return result;
    }

    public boolean running() {
        return seq != null;
    }

    public String start(String mode, int count) {
        if (seq != null) {
            return "läuft bereits";
        }
        CameraEx ex = host.cameraEx();
        if (ex == null) {
            return "Kamera nicht offen";
        }
        this.mode = mode;
        this.count = "addition".equals(mode) ? Math.max(2, Math.min(8, count)) : 1;
        received = 0;
        acc = null;
        host.stopLiveView();
        try {
            seq = CameraSequence.open(ex);
            CameraSequence.Options o = new CameraSequence.Options();
            o.setOption("AUTO_RELEASE_LOCK_ENABLED", true);
            seq.setShutterSequenceCallback(this, o);
            seq.setReleaseLock(false);
        } catch (Throwable t) {
            Log.error("rawlab open", t);
            finish("Fehler beim Öffnen: " + t);
            return result;
        }
        result = "läuft: " + mode + ", " + this.count + " Bild(er)";
        Log.info("rawlab start " + mode + " count=" + this.count);
        t0 = SystemClock.uptimeMillis();
        shoot();
        return result;
    }

    private void shoot() {
        if (seq == null) {
            return;
        }
        if (host.isCapturing()) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    shoot();
                }
            }, 200);
            return;
        }
        if (!host.captureSingle()) {
            finish("Auslösen abgelehnt");
            return;
        }
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, 20000);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            Log.info("rawlab watchdog: no callback");
            finish("kein RAW-Rückruf nach 20 s (" + received + " erhalten)");
        }
    };

    @Override
    public void onShutterSequence(CameraSequence.RawData raw, CameraSequence sequence) {
        handler.removeCallbacks(watchdog);
        received++;
        long t = SystemClock.uptimeMillis();
        try {
            CameraSequence.RawDataInfo i = raw != null ? raw.getRawDataInfo() : null;
            if (i != null) {
                Log.info("rawlab raw #" + received + " canvas=" + i.canvasSizeX + "x" + i.canvasSizeY
                        + " valid=" + i.validSizeX + "x" + i.validSizeY + "+" + i.validOffsetX + "," + i.validOffsetY
                        + " exp=" + i.expBit + " comp=" + i.compMode + " color=" + i.firstColor
                        + " wb=" + i.wbR + "/" + i.wbB + " clip=" + i.clpR + "," + i.clpGr + "," + i.clpGb + "," + i.clpB);
            } else {
                Log.info("rawlab raw #" + received + " without info, raw=" + raw);
            }
        } catch (Throwable e) {
            Log.error("rawlab info", e);
        }
        if (raw == null) {
            finish("RAW-Daten leer");
            return;
        }
        try {
            if ("addition".equals(mode)) {
                addition(raw, sequence);
            } else {
                develop(raw, sequence);
            }
        } catch (Throwable e) {
            Log.error("rawlab " + mode, e);
            release(raw);
            release(acc);
            acc = null;
            finish("Fehler: " + e);
        }
        Log.info("rawlab callback took " + (SystemClock.uptimeMillis() - t) + " ms");
    }

    private void develop(CameraSequence.RawData raw, CameraSequence sequence) {
        CameraSequence.DefaultDevelopFilter f = new CameraSequence.DefaultDevelopFilter();
        try {
            f.setSource(raw, true);
            long t = SystemClock.uptimeMillis();
            boolean ok = f.execute();
            Log.info("rawlab develop execute=" + ok + " in " + (SystemClock.uptimeMillis() - t) + " ms, outputs=" + describe(f));
            if (!ok) {
                finish("Entwicklung fehlgeschlagen");
                return;
            }
            OptimizedImage out = f.getOutput();
            Log.info("rawlab image " + out.getWidth() + "x" + out.getHeight() + " format=" + out.getImageFormat());
            sequence.storeImage(out, true);
            finish("Durchreiche ok: " + out.getWidth() + "x" + out.getHeight() + " gespeichert");
        } finally {
            f.release();
        }
    }

    private void addition(CameraSequence.RawData raw, CameraSequence sequence) {
        if (acc == null) {
            acc = raw;
            next();
            return;
        }
        boolean last = received >= count;
        Additive f = new Additive();
        try {
            if (acc instanceof CameraSequence.RawData) {
                f.setSource(0, (CameraSequence.RawData) acc, true);
            } else {
                f.setSource(0, (OptimizedImage) acc, true);
            }
            f.setSource(1, raw, true);
            f.setLastFlag(last);
            long t = SystemClock.uptimeMillis();
            boolean ok = f.execute();
            Log.info("rawlab addition #" + received + " last=" + last + " execute=" + ok + " in "
                    + (SystemClock.uptimeMillis() - t) + " ms, outputs=" + describe(f));
            if (!ok) {
                acc = null;
                finish("Addition fehlgeschlagen bei Bild " + received);
                return;
            }
            DeviceMemory[] out = f.outputs();
            acc = out.length > 0 ? out[0] : null;
            if (acc == null) {
                finish("Addition ohne Ergebnis bei Bild " + received);
                return;
            }
            if (!last) {
                next();
                return;
            }
            if (acc instanceof OptimizedImage) {
                OptimizedImage img = (OptimizedImage) acc;
                acc = null;
                sequence.storeImage(img, true);
                finish("Addition ok: " + count + " Bilder, " + img.getWidth() + "x" + img.getHeight() + " gespeichert");
            } else if (acc instanceof CameraSequence.RawData) {
                CameraSequence.RawData r = (CameraSequence.RawData) acc;
                acc = null;
                sequence.storeImage(r, true);
                finish("Addition ok: " + count + " Bilder, als RAW gespeichert");
            } else {
                release(acc);
                acc = null;
                finish("unbekanntes Ergebnis " + out[0].getClass().getName());
            }
        } finally {
            f.release();
        }
    }

    private void next() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                shoot();
            }
        }, 300);
    }

    private void finish(String text) {
        handler.removeCallbacks(watchdog);
        result = text + " (" + (SystemClock.uptimeMillis() - t0) + " ms)";
        Log.info("rawlab end: " + result);
        if (seq != null) {
            try {
                seq.setShutterSequenceCallback(null);
            } catch (Throwable t) {
                Log.error("rawlab callback off", t);
            }
            try {
                seq.release();
            } catch (Throwable t) {
                Log.error("rawlab release", t);
            }
            seq = null;
        }
    }

    private static void release(DeviceMemory m) {
        if (m != null) {
            try {
                m.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String describe(ImageFilter f) {
        StringBuilder b = new StringBuilder();
        for (DeviceMemory m : outputsOf(f)) {
            b.append(m == null ? "null" : m.getClass().getSimpleName()).append(' ');
        }
        return b.toString().trim();
    }

    /** The filter keeps its results in a protected list; getOutput() only returns images */
    @SuppressWarnings("unchecked")
    static DeviceMemory[] outputsOf(ImageFilter f) {
        try {
            Field fl = ImageFilter.class.getDeclaredField("mOutputs");
            fl.setAccessible(true);
            Vector<DeviceMemory> v = (Vector<DeviceMemory>) fl.get(f);
            return v.toArray(new DeviceMemory[v.size()]);
        } catch (Throwable t) {
            Log.error("outputs", t);
            return new DeviceMemory[0];
        }
    }

    static final class Additive extends CameraSequence.RawRawAdditionNRDevelopFilter {
        DeviceMemory[] outputs() {
            return outputsOf(this);
        }
    }
}
