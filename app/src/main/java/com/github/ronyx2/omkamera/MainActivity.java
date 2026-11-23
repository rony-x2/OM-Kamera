package com.github.ronyx2.omkamera;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.github.ma1co.openmemories.framework.DeviceInfo;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Single activity: live view at the bottom, overlay with the own interface on top.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraSession.Listener, Ui.Host, McpServer.Host {
    private CameraSession session;
    private Ui ui;
    private OverlayView overlay;
    private SurfaceHolder holder;
    private boolean surfaceReady;
    private final Beep focusBeep = new Beep();
    private WriteMonitor writeMonitor;
    private final Handler handler = new Handler();
    private Network network;
    private DebugServer debugServer;
    private Sequencer sequencer;
    private LiveAnalysis liveAnalysis;
    private Processor processor;
    private AutoIso autoIso;
    private CameraClock clock;
    private FtpUpload ftp;
    private final McpServer mcp = new McpServer(this);

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
            int level = scale > 0 ? intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale : -1;
            boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            // Raw values once per change of level or plugged state (to understand dummy batteries)
            String raw = level + "/" + plugged;
            if (!raw.equals(lastBatteryRaw)) {
                lastBatteryRaw = raw;
                StringBuilder sb = new StringBuilder("battery");
                android.os.Bundle b = intent.getExtras();
                if (b != null) {
                    for (String k : b.keySet()) {
                        sb.append(' ').append(k).append('=').append(b.get(k));
                    }
                }
                Log.info(sb.toString());
            }
            ui.setBattery(level, plugged, mv);
        }
    };

    private String lastBatteryRaw;
    private long lastActivity;
    /** Last auto power off state sent to the camera, null = not sent yet */
    private Boolean apoState;

    /** Checks the own standby timer of the app. */
    private final Runnable standbyTick = new Runnable() {
        @Override
        public void run() {
            applyStandby();
            handler.postDelayed(this, 15000);
        }
    };

    /**
     * The app suspends the camera's power saving while it is used and hands it back after
     * the chosen idle time; then the camera's own power save time applies.
     */
    @Override
    public void applyLanguage() {
        L.init(this, getSharedPreferences("ui", MODE_PRIVATE).getInt("lang", 0));
        Log.info("Sprache: " + (L.english() ? "English" : "Deutsch"));
        redraw();
    }

    @Override
    public void applyStandby() {
        int minutes = ui.standbyMinutes();
        boolean apo;
        if (minutes < 0) {
            apo = true;
        } else if (minutes == 0) {
            apo = false;
        } else {
            apo = SystemClock.uptimeMillis() - lastActivity > minutes * 60000L;
        }
        if (apoState == null || apoState != apo) {
            SonySystem.setAutoPowerOff(this, apo);
            apoState = apo;
        }
        handler.removeCallbacks(standbyTick);
        if (minutes > 0) {
            handler.postDelayed(standbyTick, 15000);
        }
    }

    /** Redraws regularly while WLAN is on, so its status in the menu stays current. */
    private final Runnable networkTick = new Runnable() {
        @Override
        public void run() {
            redraw();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.init();

        FrameLayout root = new FrameLayout(this);
        SurfaceView surface = new SurfaceView(this);
        holder = surface.getHolder();
        // Needed on Android 2.3 for the camera preview
        //noinspection deprecation
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        holder.addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new OverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        session = new CameraSession(this);
        L.init(this, getSharedPreferences("ui", MODE_PRIVATE).getInt("lang", 0));
        Settings.init(getSharedPreferences("ui", MODE_PRIVATE));
        ui = new Ui(session, new FnLayout(this), this, getSharedPreferences("ui", MODE_PRIVATE));
        sequencer = new Sequencer(session, getSharedPreferences("ui", MODE_PRIVATE), new Sequencer.Host() {
            @Override
            public void redraw() {
                MainActivity.this.redraw();
            }

            @Override
            public void message(String text, boolean warn) {
                ui.showMessage(text, warn);
            }

            @Override
            public void keepAwake() {
                lastActivity = SystemClock.uptimeMillis();
            }

            @Override
            public void startFocus() {
                session.startFocus();
            }

            @Override
            public void cancelFocus() {
                session.cancelFocus();
                ui.onFocusReleased();
            }
        });
        ui.setSequencer(sequencer);
        overlay.setUi(ui);
        liveAnalysis = new LiveAnalysis(session, new LiveAnalysis.Host() {
            @Override
            public int zebraLevel() {
                return ui.zebraLevel();
            }

            @Override
            public int peakingLevel() {
                return ui.peakingLevel();
            }

            @Override
            public int peakingColor() {
                return ui.peakingColor();
            }

            @Override
            public boolean rgbHistogram() {
                return ui.histo() == Ui.Histo.RGB && session.histogramRgbMissing();
            }

            @Override
            public boolean liveVisible() {
                return ui.liveAnalysisVisible() && !sequencer.isRunning();
            }

            @Override
            public void maskReady() {
                overlay.postInvalidate();
            }
        });
        overlay.setAnalysis(liveAnalysis);
        network = new Network(this);
        writeMonitor = new WriteMonitor(new WriteMonitor.Listener() {
            @Override
            public void onWritingChanged(boolean writing) {
                ui.setWriting(writing);
                if (!writing && processor != null) {
                    processor.onCardWritten(ui.autoExport());
                }
            }
        });
        processor = new Processor(new Processor.Host() {
            @Override
            public TiffOut.Lens lens() {
                return session.lensForFiles();
            }

            @Override
            public String version() {
                return BuildConfig.VERSION_NAME;
            }

            @Override
            public void message(final String text, final boolean warn) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        ui.showMessage(text, warn);
                        redraw();
                    }
                });
            }

            @Override
            public void keepAwake() {
                lastActivity = SystemClock.uptimeMillis();
            }

            @Override
            public void redraw() {
                overlay.postInvalidate();
            }

            @Override
            public int pngBits() {
                return ui.pngBits();
            }

            @Override
            public int heifQuality() {
                return ui.heifQuality();
            }

            @Override
            public double heifScale() {
                return ui.heifScale();
            }

            @Override
            public int avifThreads() {
                return ui.avifThreads();
            }

            @Override
            public int batteryLevel() {
                return ui.batteryLevel();
            }
        });
        ui.setProcessor(processor);
        autoIso = new AutoIso(session, getSharedPreferences("ui", MODE_PRIVATE), new AutoIso.Gate() {
            @Override
            public boolean allowed() {
                Ui.State st = ui.state();
                return (st == Ui.State.LIVE || st == Ui.State.QUICK || st == Ui.State.AF_MOVE) && !ui.aeLocked()
                        && !sequencer.isRunning();
            }
        });
        ui.setAutoIso(autoIso);
        ftp = new FtpUpload(getSharedPreferences("ui", MODE_PRIVATE), new FtpUpload.Host() {
            @Override
            public boolean wifiConnected() {
                return network.ip() != null;
            }

            @Override
            public void message(final String text, final boolean warn) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        ui.showMessage(text, warn);
                    }
                });
            }

            @Override
            public void redraw() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        MainActivity.this.redraw();
                    }
                });
            }
        });
        clock = new CameraClock(getSharedPreferences("ui", MODE_PRIVATE), new CameraClock.Host() {
            @Override
            public boolean wifiConnected() {
                return network.ip() != null;
            }

            @Override
            public String gateway() {
                return network.gateway();
            }

            @Override
            public void redraw() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        MainActivity.this.redraw();
                    }
                });
            }

            @Override
            public void message(final String text, final boolean warn) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        ui.showMessage(text, warn);
                        redraw();
                    }
                });
            }
        });
        debugServer = new DebugServer(new DebugServer.Provider() {
            @Override
            public DebugServer.Response handle(DebugServer.Request request) {
                return debugHandle(request);
            }
        });
    }

    /**
     * The camera takes the drive mode from its own menu when the app starts and does not keep
     * the one chosen in the app (other settings it keeps); the app puts its choice back.
     */
    private void restoreDrive() {
        String want = Settings.savedDrive();
        if (want == null) {
            return;
        }
        try {
            Setting d = Settings.get("drive");
            String now = d.safeGet(session.current());
            if (!want.equals(now) && d.safeValues(session.current()).contains(want)) {
                Log.info("drive restore " + now + " -> " + want);
                d.write(session, want);
            }
        } catch (Throwable t) {
            Log.error("drive restore", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.info("onResume");
        SonySystem.notifyAppInfo(this, ui.resumePlay());
        Log.info("app info, Wiedergabe-Taste zurück in die App: " + ui.resumePlay());
        lastActivity = SystemClock.uptimeMillis();
        apoState = null;
        applyStandby();
        SonySystem.useOverlayFrameBuffer();
        focusBeep.prepare(Beep.FOCUS_LOCK);
        try {
            session.open();
        } catch (Throwable t) {
            Log.error("open", t);
            ui.showMessage("Kamera konnte nicht geöffnet werden", true);
            return;
        }
        if (surfaceReady) {
            session.startPreview(holder, !ui.appShutter());
        }
        // Follow the mode dial position the camera had when the app started
        int dial = ModeDial.position();
        if (dial > 0) {
            ui.onModeDial(dial);
        }
        restoreDrive();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        applyNetwork();
        applyDisplay();
        autoIso.apply();
        clock.start();
        ftp.start();
        handler.post(cardTick);
        redraw();
    }

    @Override
    protected void onPause() {
        Log.info("onPause");
        AppListReceiver.markLeft(this);
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Throwable ignored) {
        }
        handler.removeCallbacks(networkTick);
        handler.removeCallbacks(standbyTick);
        handler.removeCallbacks(cardTick);
        stopLevel();
        liveAnalysis.stop();
        autoIso.stop();
        clock.stop();
        ftp.stop();
        sequencer.stop("abgebrochen (App beendet)");
        SonySystem.setAutoPowerOff(this, true);
        debugServer.stop();
        if (ui.wifiEnabled()) {
            // The camera's own interface expects WLAN to be off
            network.setEnabled(false);
        }
        writeMonitor.reset();
        session.close();
        focusBeep.release();
        super.onPause();
    }

    // ---- surface ----

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (session.isOpen()) {
            session.startPreview(h, !ui.appShutter());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        Log.info("surface " + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
    }

    // ---- keys ----

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Keys.Key key = Keys.map(event);
        lastActivity = SystemClock.uptimeMillis();
        if (apoState != null && apoState && ui.standbyMinutes() > 0) {
            applyStandby();
        }
        // Every key with its codes, to learn the layout of each camera model
        Log.info("key " + key + " scan=" + event.getScanCode() + " code=" + keyCode
                + (event.getRepeatCount() > 0 ? " repeat=" + event.getRepeatCount() : ""));
        int scan = event.getScanCode();
        // Buttons on the lens: remember them for the lens page
        if (scan == 653) {
            session.lensKeySeen("Fokus-Haltetaste");
        } else if (scan == 610 || scan == 611) {
            session.lensKeySeen("Zoomwippe");
        } else if (scan >= 650 && scan <= 652) {
            session.lensKeySeen("Blendenring");
        }
        if (scan == ModeDial.CHANGED) {
            // The dial reports a change; the position is read from the key status
            ui.onModeDial(ModeDial.position());
            return true;
        }
        if (ModeDial.isModeDial(scan)) {
            ui.onModeDial(scan);
            return true;
        }
        if (!session.isDirectShutter() && event.getRepeatCount() == 0) {
            if (key == Keys.Key.SHUTTER_HALF) {
                ui.onKey(key);
                session.startFocus();
                return true;
            }
            if (key == Keys.Key.SHUTTER_FULL) {
                ui.onKey(key);
                if (sequencer.mode() != Sequencer.Mode.OFF || sequencer.isRunning()) {
                    // A selected sequence starts (or stops) instead of a single picture
                    sequencer.onFullPress();
                    redraw();
                } else if (session.longSecs() > 0) {
                    // A time beyond 30" from the shutter dial: BULB timed by the app
                    ui.showMessage(sequencer.startLongExposure(), false);
                    redraw();
                } else {
                    session.capture(isBurst());
                }
                return true;
            }
        }
        if (session.selfTimerRunning() && key != Keys.Key.SHUTTER_HALF && key != Keys.Key.SHUTTER_FULL) {
            // Any other key stops the countdown, like on the camera
            session.cancelSelfTimer();
            ui.showMessage("Selbstauslöser abgebrochen", false);
            return true;
        }
        if (event.getRepeatCount() > 0 && KeyFunctions.assignable(key) && ui.state() == Ui.State.LIVE) {
            // Holding a custom key must not repeat its function
            return true;
        }
        if (key == Keys.Key.OTHER) {
            return super.onKeyDown(keyCode, event);
        }
        try {
            if (ui.onKey(key)) {
                return true;
            }
        } catch (Throwable t) {
            Log.error("key " + key, t);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Keys.Key key = Keys.map(event);
        if (KeyFunctions.assignable(key)) {
            ui.onKeyRelease(key);
        }
        if (!session.isDirectShutter()) {
            if (key == Keys.Key.SHUTTER_HALF) {
                session.cancelFocus();
                ui.onFocusReleased();
                return true;
            }
            if (key == Keys.Key.SHUTTER_FULL) {
                if (!sequencer.isRunning()) {
                    session.stopCapture();
                }
                return true;
            }
        }
        if (key == Keys.Key.SHUTTER_HALF || key == Keys.Key.SHUTTER_FULL || key == Keys.Key.OTHER) {
            return super.onKeyUp(keyCode, event);
        }
        // Key up of keys handled on key down must not reach the system (e.g. MENU would close the app)
        return true;
    }

    private boolean isBurst() {
        return DriveSetting.isContinuous(Settings.get("drive").safeGet(session.current()));
    }

    // ---- callbacks ----

    @Override
    public void onParamsChanged() {
        redraw();
    }

    @Override
    public void onExposureChanged() {
        redraw();
    }

    private String lastFocusLog;

    @Override
    public void onFocus(int status, int[] indices) {
        // AF-C reports continuously; only changes are logged
        String line = "focus status=" + status + " frames=" + Arrays.toString(indices);
        if (!line.equals(lastFocusLog)) {
            Log.info(line);
            lastFocusLog = line;
        }
        ui.onFocus(status, indices);
    }

    @Override
    public void onFocusResult(boolean success) {
        ui.onFocusResult(success);
    }

    @Override
    public void playFocusBeep(Beep.Mode mode) {
        focusBeep.play(mode);
    }

    @Override
    public void onTimerTick(int remaining) {
        // Beep with the camera's setting for focus beeps; without beep the countdown is only shown
        if (ui.beepMode() != Beep.Mode.OFF) {
            focusBeep.play(Beep.Mode.TONE);
        }
        redraw();
    }

    @Override
    public void onShot() {
        sequencer.onShot();
        writeMonitor.onShot();
    }

    @Override
    public void onStored(String file) {
        Log.info("stored " + file);
        writeMonitor.onStored();
    }

    // ---- network and debug server ----

    @Override
    public void applyNetwork() {
        network.setEnabled(ui.wifiEnabled());
        if (ui.debugEnabled()) {
            debugServer.start();
        } else {
            debugServer.stop();
        }
        handler.removeCallbacks(networkTick);
        if (ui.wifiEnabled()) {
            handler.postDelayed(networkTick, 2000);
        }
    }

    @Override
    public String wifiStatus() {
        return ui.wifiEnabled() ? network.status() : "aus";
    }

    // ---- display helpers: histogram, level sensor, free space ----

    @Override
    public void applyDisplay() {
        session.setHistogramEnabled(ui.histo() != Ui.Histo.OFF);
        liveAnalysis.update();
        if (ui.levelEnabled()) {
            startLevel();
        } else {
            stopLevel();
        }
    }

    @Override
    public void later(Runnable r, long delayMs) {
        handler.postDelayed(r, delayMs);
    }

    @Override
    public void cancel(Runnable r) {
        handler.removeCallbacks(r);
    }

    @Override
    public void onFocusRing(boolean turning) {
        if (turning) {
            lastActivity = SystemClock.uptimeMillis();
        }
        ui.onFocusRing(turning);
    }

    @Override
    public void onMagnificationChanged(boolean on) {
        ui.onMagnificationChanged(on);
    }

    @Override
    public void onHistogram() {
        if (ui.state() == Ui.State.LIVE) {
            redraw();
        }
    }

    private android.hardware.SensorManager sensors;
    private final float[] gravity = new float[3];
    private boolean gravityValid;
    private boolean levelHintShown;
    private int levelEvents;

    private final android.hardware.SensorEventListener levelListener = new android.hardware.SensorEventListener() {
        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            if (e.sensor.getType() != android.hardware.Sensor.TYPE_ACCELEROMETER) {
                return;
            }
            if (levelEvents++ == 0) {
                Log.info("level first values " + e.values[0] + " " + e.values[1] + " " + e.values[2]);
            }
            // Low-pass filter, so the line does not shake
            float k = gravityValid ? 0.2f : 1f;
            for (int i = 0; i < 3; i++) {
                gravity[i] += k * (e.values[i] - gravity[i]);
            }
            gravityValid = true;
            float x = gravity[0], y = gravity[1], z = gravity[2];
            // Roll: rotation around the lens axis; pitch: lens up or down
            float roll = (float) Math.toDegrees(Math.atan2(x, y));
            float pitch = (float) Math.toDegrees(Math.atan2(z, Math.sqrt(x * x + y * y)));
            ui.setTilt(roll, pitch);
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
        }
    };

    private void startLevel() {
        if (sensors == null) {
            sensors = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
        }
        if (sensors == null) {
            return;
        }
        android.hardware.Sensor s = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
        ui.markLevelStart();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Some models (a6000) have a sensor that apps cannot read: say so once, briefly
                if (ui.levelSilent() && !levelHintShown) {
                    levelHintShown = true;
                    ui.showMessage("Wasserwaage: Kamera liefert keine Sensordaten", true);
                }
            }
        }, 3200);
        if (s != null) {
            gravityValid = false;
            levelEvents = 0;
            // The camera's sensor driver only delivers at the GAME rate, like Sony's own level does it;
            // Sony also registers the (dummy) magnetic sensor at the same time
            boolean ok = sensors.registerListener(levelListener, s, android.hardware.SensorManager.SENSOR_DELAY_GAME);
            android.hardware.Sensor m = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD);
            if (m != null) {
                sensors.registerListener(levelListener, m, android.hardware.SensorManager.SENSOR_DELAY_GAME);
            }
            Log.info("level sensor " + s.getName() + " registered=" + ok);
        } else {
            Log.info("no acceleration sensor");
        }
        // Diagnosis: listen to every sensor at the fastest rate and report which one delivers
        for (android.hardware.Sensor any : sensors.getSensorList(android.hardware.Sensor.TYPE_ALL)) {
            boolean ok = sensors.registerListener(probeListener, any, android.hardware.SensorManager.SENSOR_DELAY_FASTEST);
            Log.info("probe " + any.getName() + " type=" + any.getType() + " registered=" + ok);
        }
        probeCounts.clear();
        handler.postDelayed(probeReport, 5000);
    }

    private void stopLevel() {
        if (sensors != null) {
            sensors.unregisterListener(levelListener);
            sensors.unregisterListener(probeListener);
        }
        handler.removeCallbacks(probeReport);
    }

    private final java.util.Map<Integer, Integer> probeCounts = new java.util.HashMap<Integer, Integer>();

    private final android.hardware.SensorEventListener probeListener = new android.hardware.SensorEventListener() {
        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            int type = e.sensor.getType();
            Integer n = probeCounts.get(type);
            probeCounts.put(type, n == null ? 1 : n + 1);
            if (n == null) {
                Log.info("probe first event type=" + type + " values=" + java.util.Arrays.toString(e.values));
            }
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
            Log.info("probe accuracy type=" + sensor.getType() + " " + accuracy);
        }
    };

    private final Runnable probeReport = new Runnable() {
        @Override
        public void run() {
            Log.info("probe events after 5 s: " + probeCounts);
            if (sensors != null) {
                sensors.unregisterListener(probeListener);
            }
        }
    };

    @Override
    public String sequenceRelease() {
        if (sequencer.mode() == Sequencer.Mode.OFF && !sequencer.isRunning()) {
            if (session.longSecs() > 0) {
                // A time beyond 30" from the shutter dial, as with the shutter button
                String r = sequencer.startLongExposure();
                redraw();
                return r;
            }
            return null;
        }
        String r = sequencer.onFullPress();
        redraw();
        return r;
    }

    @Override
    public String sequenceStatus() {
        return sequencer.status();
    }

    @Override
    public java.util.List<String> warnings() {
        return overlay.warnings();
    }

    /** Raw filtered acceleration values, for calibrating the level over MCP */
    @Override
    public float[] gravity() {
        return gravityValid ? gravity.clone() : null;
    }

    /** Remaining pictures on the card, estimated from free space and the current quality */
    private final Runnable cardTick = new Runnable() {
        @Override
        public void run() {
            ui.setRemainingShots(remainingShots());
            handler.postDelayed(this, 5000);
        }
    };

    private int remainingShots() {
        try {
            android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
            long free = (long) fs.getAvailableBlocks() * fs.getBlockSize();
            String q = Settings.get("quality").safeGet(session.current());
            // Typical sizes of the a6000 (24 MP) in KB, measured with OM Kamera
            long kb = 5600;
            if ("raw".equals(q)) {
                kb = 24300;
            } else if ("rawjpeg".equals(q)) {
                kb = 31000;
            } else if (q != null && q.startsWith("jpeg-")) {
                int level = Integer.parseInt(q.substring(5));
                kb = level >= 90 ? 7500 : level >= 50 ? 5600 : 3500;
            }
            return (int) Math.min(99999, free / 1024 / kb);
        } catch (Throwable t) {
            return -1;
        }
    }

    private Memories memories;

    @Override
    public Memories memories() {
        if (memories == null) {
            memories = new Memories(session, getSharedPreferences("ui", MODE_PRIVATE));
        }
        return memories;
    }

    @Override
    public String[] wifiDetails() {
        return network.details();
    }

    @Override
    public CameraClock clock() {
        return clock;
    }

    @Override
    public FtpUpload ftp() {
        return ftp;
    }

    @Override
    public boolean wifiConnected() {
        return network.ip() != null;
    }

    @Override
    public String debugAddress() {
        String ip = network.ip();
        return debugServer.isRunning() && ip != null ? ip + ":" + DebugServer.PORT : null;
    }

    /** Runs code on the main thread and waits for its result (camera and UI are not thread-safe). */
    @Override
    public CameraSession session() {
        return session;
    }

    @Override
    public Ui ui() {
        return ui;
    }

    @Override
    public String version() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public byte[] screenPng() {
        return overlayPng(true);
    }

    @Override
    public <T> T onMain(final java.util.concurrent.Callable<T> task) {
        final Object[] result = new Object[1];
        final CountDownLatch done = new CountDownLatch(1);
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = task.call();
                } catch (Throwable t) {
                    Log.error("main task", t);
                }
                done.countDown();
            }
        });
        try {
            done.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        @SuppressWarnings("unchecked")
        T r = (T) result[0];
        return r;
    }

    @Override
    public String asset(String name) {
        java.io.InputStream in = null;
        try {
            in = getAssets().open(name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "Asset fehlt: " + name;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static DebugServer.Response text(String body) {
        return DebugServer.Response.text(200, "text/plain", body);
    }

    /** FTP settings for the remote page, without the password */
    private String ftpJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("host", ftp.server());
            o.put("port", ftp.port());
            o.put("user", ftp.user());
            o.put("pass", ftp.hasPassword());
            o.put("dir", ftp.dir());
            o.put("what", ftp.what());
            o.put("results", ftp.results());
            o.put("on", ftp.on());
            o.put("state", L.t(ftp.summary()));
            return "\"ftp\":" + o.toString();
        } catch (org.json.JSONException e) {
            return "\"ftp\":null";
        }
    }

    private static String reflect(String name) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> c = Class.forName(name);
            sb.append(c).append(" extends ").append(c.getSuperclass()).append('\n');
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                sb.append("F ").append(java.lang.reflect.Modifier.toString(f.getModifiers())).append(' ')
                        .append(f.getType().getName()).append(' ').append(f.getName()).append('\n');
            }
            for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                sb.append("C ").append(k).append('\n');
            }
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                sb.append("M ").append(m).append('\n');
            }
            for (Class<?> i : c.getDeclaredClasses()) {
                sb.append("K ").append(i.getName()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("Fehler: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static DebugServer.Response json(String body) {
        return DebugServer.Response.text(200, "application/json", body);
    }

    /**
     * Debug and remote control requests. Reading pages are open while the debug server runs;
     * live view and every action need remote control switched on and the code shown on the camera.
     */
    private DebugServer.Response debugHandle(DebugServer.Request r) {
        String path = r.path;
        if (path.equals("/")) {
            return DebugServer.Response.text(200, "text/html", asset(L.english() ? "debug_en.html" : "debug.html"));
        }
        if (path.equals("/remote")) {
            return DebugServer.Response.text(200, "text/html", asset(L.english() ? "remote_en.html" : "remote.html"));
        }
        if (path.equals("/log")) {
            return text(Log.tail(64 * 1024));
        }
        if (path.equals("/status")) {
            return text(onMain(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    return L.lines(statusText());
                }
            }));
        }
        if (path.equals("/params")) {
            return text(onMain(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    return paramsText();
                }
            }));
        }
        if (path.equals("/api/state")) {
            return json(onMain(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() throws Exception {
                    return stateJson();
                }
            }));
        }
        if (path.equals("/mcp")) {
            return mcp.handle(r);
        }
        if (!path.startsWith("/api/") && !path.equals("/live.jpg") && !path.equals("/overlay.png")) {
            return null;
        }
        // Everything below controls the camera or shows its picture
        if (!ui.remoteEnabled()) {
            return DebugServer.Response.text(403, "text/plain", "Fernsteuerung ist an der Kamera ausgeschaltet");
        }
        if (ui.remoteCode() == null || !ui.remoteCode().equals(r.param("k"))) {
            return DebugServer.Response.text(403, "text/plain", "Code fehlt oder falsch");
        }
        if (path.equals("/api/i18n")) {
            // Texts that stayed German in the English display (for completing the translation)
            return DebugServer.Response.text(200, "text/plain; charset=utf-8", L.missing());
        }
        if (path.equals("/api/system")) {
            // Fixed read-only queries with root rights on the camera's Linux host; no free commands
            String was = String.valueOf(r.param("was"));
            if (r.param("was") == null) {
                return DebugServer.Response.text(200, "text/plain; charset=utf-8",
                        "root: " + RootShell.status() + "\nAbfragen: " + android.text.TextUtils.join(", ", RootShell.names()) + "\n");
            }
            return DebugServer.Response.text(200, "text/plain; charset=utf-8", RootShell.run(this, was, 15000));
        }
        if (path.equals("/api/reflect")) {
            // Read-only: lists what a class on the camera offers (methods, fields), nothing is called
            return DebugServer.Response.text(200, "text/plain; charset=utf-8", reflect(String.valueOf(r.param("cls"))));
        }
        if (path.equals("/api/bench")) {
            // Speed test on the newest ARW: card reading vs. decoding
            try {
                java.util.List<java.io.File> f = Processor.newest(1);
                if (f.isEmpty()) {
                    return DebugServer.Response.text(404, "text/plain", "keine ARW-Datei");
                }
                int rows = 1024;
                try {
                    rows = Integer.parseInt(String.valueOf(r.param("zeilen")));
                } catch (NumberFormatException ignored) {
                }
                RawFile rf = new RawFile(f.get(0));
                String res = f.get(0).getName() + ": " + rf.bench(rows);
                rf.close();
                Log.info("bench " + res);
                return DebugServer.Response.text(200, "text/plain; charset=utf-8", res + "\n");
            } catch (Throwable t) {
                return DebugServer.Response.text(500, "text/plain", "Fehler: " + t + "\n");
            }
        }
        if (path.equals("/api/ls")) {
            StringBuilder sb = new StringBuilder();
            java.io.File[] fs = new java.io.File(String.valueOf(r.param("dir"))).listFiles();
            if (fs != null) {
                java.util.Arrays.sort(fs);
                for (java.io.File f : fs) {
                    sb.append(f.isDirectory() ? "d " : "- ").append(f.length()).append(' ').append(f.getName()).append('\n');
                }
            }
            return DebugServer.Response.text(200, "text/plain; charset=utf-8", sb.length() > 0 ? sb.toString() : "leer oder nicht lesbar\n");
        }
        if (path.equals("/api/file")) {
            // A file from the card (film, DNG ...), streamed for checking it on the PC
            java.io.File root = android.os.Environment.getExternalStorageDirectory();
            String rel = String.valueOf(r.param("path")).replace("..", "");
            java.io.File f = new java.io.File(root, rel);
            if (!f.isFile()) {
                return DebugServer.Response.text(404, "text/plain", "keine Datei " + rel);
            }
            return new DebugServer.Response(f);
        }
        if (path.equals("/live.jpg")) {
            byte[] frame = session.liveFrame();
            return frame != null ? new DebugServer.Response(200, "image/jpeg", frame)
                    : DebugServer.Response.text(503, "text/plain", "kein Bild");
        }
        if (path.equals("/overlay.png")) {
            byte[] png = onMain(new java.util.concurrent.Callable<byte[]>() {
                @Override
                public byte[] call() {
                    return overlayPng();
                }
            });
            return png != null ? new DebugServer.Response(200, "image/png", png)
                    : DebugServer.Response.text(503, "text/plain", "kein Bild");
        }
        if (path.equals("/api/longtimes")) {
            // Own exposure times after 30" ("40, 45, 1:30, 5m"); without v only the list
            String msg = null;
            if (r.param("v") != null) {
                msg = sequencer.setLongTimes(Sequencer.parseLongTimes(r.param("v")), true);
            }
            StringBuilder sb = new StringBuilder();
            for (int x : sequencer.dialLongTimes()) {
                sb.append(sb.length() > 0 ? ", " : "").append(x);
            }
            return json("{\"result\":\"ok\",\"text\":" + org.json.JSONObject.quote(msg == null ? "" : L.t(msg))
                    + ",\"longtimes\":" + org.json.JSONObject.quote(sb.toString()) + "}");
        }
        if (path.equals("/api/ftp")) {
            // FTP settings from the remote page; without parameters only the current state
            if (r.param("host") != null || r.param("on") != null || r.param("test") != null) {
                Integer port = null, what = null;
                try {
                    port = r.param("port") != null && r.param("port").length() > 0 ? Integer.valueOf(r.param("port")) : null;
                    what = r.param("what") != null ? Integer.valueOf(r.param("what")) : null;
                } catch (NumberFormatException ignored) {
                }
                Boolean results = r.param("results") != null ? Boolean.valueOf("1".equals(r.param("results"))) : null;
                ftp.configure(r.param("host"), port, r.param("user"), r.param("pass"), r.param("dir"), what, results);
                String msg = "gespeichert";
                if (r.param("test") != null) {
                    msg = ftp.test();
                }
                if (r.param("on") != null) {
                    ftp.setOn("1".equals(r.param("on")));
                }
                Log.info("remote /api/ftp " + ftp.server() + ":" + ftp.port() + " " + ftp.dir() + " on=" + ftp.on() + " -> " + msg);
                redraw();
                return json("{\"result\":\"ok\",\"text\":" + org.json.JSONObject.quote(L.t(msg)) + "," + ftpJson() + "}");
            }
            return json("{\"result\":\"ok\"," + ftpJson() + "}");
        }
        if (path.equals("/api/clock")) {
            // Takes the browser's time for the camera clock (when there is no time server)
            long t;
            try {
                t = Long.parseLong(String.valueOf(r.param("t")));
            } catch (NumberFormatException e) {
                return json("{\"result\":\"Zeit fehlt\"}");
            }
            String res = clock.syncFrom(t, "vom Browser");
            Log.info("remote /api/clock -> " + res);
            return json("{\"result\":\"ok\",\"text\":" + org.json.JSONObject.quote(L.t(res)) + "}");
        }
        final DebugServer.Request req = r;
        String result = onMain(new java.util.concurrent.Callable<String>() {
            @Override
            public String call() {
                return remoteAction(req);
            }
        });
        Log.info("remote " + path + " " + req.query + " -> " + result);
        return json("{\"result\":" + org.json.JSONObject.quote(L.t(result != null ? result : "Zeitüberschreitung")) + "}");
    }

    /** Executes a remote action on the main thread. */
    private String remoteAction(DebugServer.Request r) {
        String path = r.path;
        if (path.equals("/api/set")) {
            Setting s = Settings.get(r.param("id"));
            if (s == null || r.param("value") == null) {
                return "unbekannte Einstellung";
            }
            CameraSession.Result res = s.write(session, r.param("value"));
            redraw();
            return res.ok ? "ok" : "gesperrt" + (res.inhibition != 0 ? " (Grund " + res.inhibition + ")" : "");
        }
        if (path.equals("/api/exposure")) {
            int dir = "-1".equals(r.param("dir")) ? -1 : 1;
            String what = r.param("what");
            if ("shutter".equals(what)) {
                ui.stepShutter(dir);
            } else if ("aperture".equals(what)) {
                session.adjustAperture(dir);
            } else if ("shift".equals(what)) {
                session.adjustProgramShift(dir);
            } else if ("ev".equals(what)) {
                ui.stepSetting(Settings.get("ev"), dir);
            } else {
                return "unbekannt";
            }
            redraw();
            return "ok";
        }
        if (path.equals("/api/key")) {
            // Camera keys from the browser, e.g. to open the Fn grid or the menu remotely
            Keys.Key key;
            try {
                key = Keys.Key.valueOf(String.valueOf(r.param("name")));
            } catch (IllegalArgumentException e) {
                return "unbekannte Taste";
            }
            if (key == Keys.Key.SHUTTER_HALF || key == Keys.Key.SHUTTER_FULL) {
                return "Auslöser über /api/af und /api/shoot";
            }
            if (key == Keys.Key.AEL) {
                return "AEL über /api/ael";
            }
            ui.onKey(key);
            return "ok";
        }
        if (path.equals("/api/ael")) {
            ui.onAel("1".equals(r.param("on")));
            return ui.aeLocked() ? "locked" : "unlocked";
        }
        if (path.equals("/api/fp")) {
            // Flexible spot: dx/dy in steps, or center=1
            CameraSession.Result res;
            if ("1".equals(r.param("center"))) {
                res = session.centerFocusPoint();
            } else if (r.param("x") != null && r.param("y") != null) {
                res = session.setFocusPoint(parseInt(r.param("x")), parseInt(r.param("y")));
            } else {
                int dx = parseInt(r.param("dx"));
                int dy = parseInt(r.param("dy"));
                res = session.moveFocusPoint(dx, dy);
            }
            redraw();
            int[] fp = session.focusPoint();
            return (res.ok ? "ok" : "refused " + res.inhibition) + (fp != null ? " " + fp[0] + "," + fp[1] : "");
        }
        if (path.equals("/api/af")) {
            if ("1".equals(r.param("on"))) {
                session.startFocus();
            } else {
                session.cancelFocus();
                ui.onFocusReleased();
            }
            return "ok";
        }
        if (path.equals("/api/shoot")) {
            if (session.isDirectShutter()) {
                return "Auslöser-Steuerung steht auf „Kamera direkt“";
            }
            // Focus first, then release like a full press
            session.startFocus();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    session.capture(false);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            session.cancelFocus();
                            ui.onFocusReleased();
                        }
                    }, 1500);
                }
            }, 800);
            return "ok";
        }
        return "unbekannte Aktion";
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String stateJson() throws org.json.JSONException {
        org.json.JSONObject o = new org.json.JSONObject();
        CameraSession.Params p = session.current();
        o.put("model", DeviceInfo.getInstance().getModel());
        o.put("mode", ui.modeName());
        o.put("shutter", session.shutterText());
        o.put("aperture", session.apertureText());
        Setting ev = Settings.get("ev");
        Setting iso = Settings.get("iso");
        o.put("ev", ev.currentText(p));
        o.put("iso", iso.currentText(p));
        o.put("dial1", ui.dial1Role().name());
        o.put("dial2", ui.dial2Role().name());
        o.put("battery", ui.batteryLevel());
        o.put("plugged", ui.batteryPlugged());
        o.put("writing", ui.writing());
        o.put("focus", ui.focusResult().name());
        o.put("remote", ui.remoteEnabled());
        o.put("directShutter", session.isDirectShutter());
        o.put("ui", ui.state().name());
        o.put("ael", ui.aeLocked());
        o.put("clock", CameraClock.nowUtc());
        int[] fp = session.focusPoint();
        if (fp != null) {
            o.put("focusPoint", fp[0] + "," + fp[1]);
        }
        org.json.JSONArray ff = new org.json.JSONArray();
        for (CameraSession.FocusFrame f : session.focusFrames(overlay.getWidth(), overlay.getHeight())) {
            ff.put(f.index + (f.enabled ? "*" : "") + ":" + f.rect.toShortString());
        }
        o.put("frames", ff);
        org.json.JSONArray list = new org.json.JSONArray();
        for (Setting s : Settings.all()) {
            org.json.JSONObject j = new org.json.JSONObject();
            j.put("id", s.id);
            j.put("label", L.t(s.label));
            j.put("group", L.t(s.category.title));
            boolean available = s.isAvailable(p);
            j.put("available", available);
            j.put("shown", s.isShown(p));
            if (available) {
                j.put("value", s.safeGet(p));
                j.put("display", L.t(s.currentText(p)));
                org.json.JSONArray values = new org.json.JSONArray();
                for (String v : s.safeValues(p)) {
                    org.json.JSONArray pair = new org.json.JSONArray();
                    pair.put(v);
                    pair.put(L.t(s.display(p, v)));
                    values.put(pair);
                }
                j.put("values", values);
            }
            list.put(j);
        }
        o.put("settings", list);
        return o.toString();
    }

    /** The app's overlay as PNG, as it is drawn on the camera (without the live view). */
    private byte[] overlayPng() {
        return overlayPng(false);
    }

    /** The overlay as PNG; stretched=true scales it to the panel's real aspect ratio (640x480 shown as 16:9) */
    private byte[] overlayPng(boolean stretched) {
        if (overlay.getWidth() <= 0) {
            return null;
        }
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(overlay.getWidth(), overlay.getHeight(),
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(b);
        if (stretched) {
            // Neutral grey instead of the live view, so white text stays readable
            canvas.drawColor(0xFF3A424C);
        }
        overlay.draw(canvas);
        if (stretched) {
            int w = Math.round(overlay.getWidth() * session.pixelAspect(overlay.getWidth(), overlay.getHeight()));
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(b, w, overlay.getHeight(), true);
            if (scaled != b) {
                b.recycle();
                b = scaled;
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        b.recycle();
        return out.toByteArray();
    }

    private String statusText() {
        StringBuilder sb = new StringBuilder();
        DeviceInfo d = DeviceInfo.getInstance();
        sb.append("Modell: ").append(d.getModel()).append('\n');
        sb.append("Firmware: ").append(d.getFirmwareVersion()).append('\n');
        sb.append("API-Version: ").append(d.getApiVersion()).append('\n');
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE).append('\n');
        sb.append("App: ").append(BuildConfig.VERSION_NAME).append('\n');
        sb.append("Akku: ").append(ui.batteryLevel()).append(" %").append(ui.batteryPlugged() ? " (Netz)" : "").append('\n');
        sb.append("Modus: ").append(ui.modeName()).append(" (").append(session.sceneMode()).append(")\n");
        sb.append("Moduswahlrad: ").append(ModeDial.name(ModeDial.position())).append('\n');
        sb.append("Zeit/Blende: ").append(session.shutterText()).append(' ').append(session.apertureText()).append('\n');
        sb.append("Fokus: Status ").append(ui.focusStatus()).append(" Felder ").append(Arrays.toString(ui.focusIndices())).append('\n');
        sb.append("Fokusfelder: ").append(session.focusFrames(overlay.getWidth(), overlay.getHeight()).size()).append('\n');
        sb.append("Oberfläche: ").append(overlay.getWidth()).append('x').append(overlay.getHeight()).append('\n');
        // Which sensors Android exposes on this camera (e.g. for a level or orientation display)
        try {
            android.hardware.SensorManager sm = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
            java.util.List<android.hardware.Sensor> sensors = sm.getSensorList(android.hardware.Sensor.TYPE_ALL);
            sb.append("Sensoren: ").append(sensors.isEmpty() ? "keine" : "");
            for (android.hardware.Sensor sensor : sensors) {
                sb.append(sensor.getName()).append(" (Typ ").append(sensor.getType()).append("); ");
            }
            sb.append('\n');
        } catch (Throwable t) {
            sb.append("Sensoren: Fehler ").append(t).append('\n');
        }
        sb.append('\n');
        CameraSession.Params p = session.current();
        for (Setting s : Settings.all()) {
            sb.append(s.label).append(": ");
            if (!s.isAvailable(p)) {
                sb.append("nicht verfügbar\n");
                continue;
            }
            sb.append(s.safeGet(p)).append("  [").append(android.text.TextUtils.join(", ", s.safeValues(p))).append("]\n");
        }
        return sb.toString();
    }

    private String paramsText() {
        String[] parts = session.flatten().split(";");
        Arrays.sort(parts);
        return android.text.TextUtils.join("\n", parts) + "\n";
    }

    @Override
    public void redraw() {
        if (overlay != null) {
            overlay.invalidate();
        }
    }

    @Override
    public void exitApp() {
        Log.info("exit");
        finish();
    }
}
