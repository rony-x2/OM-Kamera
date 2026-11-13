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
public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraSession.Listener, Ui.Host {
    private CameraSession session;
    private Ui ui;
    private OverlayView overlay;
    private SurfaceHolder holder;
    private boolean surfaceReady;
    private final Beep focusBeep = new Beep();
    private final Handler handler = new Handler();
    private Network network;
    private DebugServer debugServer;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
            int level = scale > 0 ? intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale : -1;
            boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            ui.setBattery(level, plugged);
        }
    };

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
        ui = new Ui(session, new FnLayout(this), this, getSharedPreferences("ui", MODE_PRIVATE));
        overlay.setUi(ui);
        network = new Network(this);
        debugServer = new DebugServer(new DebugServer.Provider() {
            @Override
            public String[] get(String path) {
                return debugContent(path);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.info("onResume");
        SonySystem.notifyAppInfo(this);
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
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        applyNetwork();
        redraw();
    }

    @Override
    protected void onPause() {
        Log.info("onPause");
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Throwable ignored) {
        }
        handler.removeCallbacks(networkTick);
        handler.removeCallbacks(standbyTick);
        SonySystem.setAutoPowerOff(this, true);
        debugServer.stop();
        if (ui.wifiEnabled()) {
            // The camera's own interface expects WLAN to be off
            network.setEnabled(false);
        }
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
                session.capture(isBurst());
                return true;
            }
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
        if (!session.isDirectShutter()) {
            if (key == Keys.Key.SHUTTER_HALF) {
                session.cancelFocus();
                ui.onFocusReleased();
                return true;
            }
            if (key == Keys.Key.SHUTTER_FULL) {
                session.stopCapture();
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
        String drive = Settings.get("drive").safeGet(session.current());
        return drive != null && (drive.contains("burst") || drive.contains("continu") || drive.contains("speed"));
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
    public void playFocusBeep() {
        focusBeep.play();
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

    @Override
    public String debugAddress() {
        String ip = network.ip();
        return debugServer.isRunning() && ip != null ? ip + ":" + DebugServer.PORT : null;
    }

    /** Content of the debug server. Camera data is collected on the main thread. */
    private String[] debugContent(String path) {
        if (path.equals("/") || path.startsWith("/?")) {
            return new String[] {"text/html", DEBUG_PAGE};
        }
        if (path.startsWith("/log")) {
            return new String[] {"text/plain", Log.tail(64 * 1024)};
        }
        if (path.startsWith("/status") || path.startsWith("/params")) {
            final boolean params = path.startsWith("/params");
            final String[] result = new String[1];
            final CountDownLatch done = new CountDownLatch(1);
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        result[0] = params ? paramsText() : statusText();
                    } catch (Throwable t) {
                        result[0] = "Fehler: " + t;
                    }
                    done.countDown();
                }
            });
            try {
                done.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return new String[] {"text/plain", result[0] != null ? result[0] : "keine Antwort"};
        }
        return null;
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

    private static final String DEBUG_PAGE = "<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>OM Kamera Debug</title>"
            + "<style>body{background:#101317;color:#e8eaed;font:15px/1.5 system-ui,sans-serif;margin:0;padding:16px}"
            + "nav a{color:#f0a030;margin-right:16px;text-decoration:none;font-weight:600}"
            + "pre{background:#181c21;border:1px solid #2a3038;border-radius:8px;padding:12px;white-space:pre-wrap;"
            + "word-break:break-all;font:13px/1.45 ui-monospace,Consolas,monospace}</style></head><body>"
            + "<h1 style=\"font-size:20px\">OM Kamera – Debug</h1>"
            + "<nav><a href=\"#\" onclick=\"show('/status');return false\">Status</a>"
            + "<a href=\"#\" onclick=\"show('/params');return false\">Parameter</a>"
            + "<a href=\"#\" onclick=\"show('/log');return false\">Log</a>"
            + "<label><input type=\"checkbox\" id=\"auto\" checked> automatisch aktualisieren</label></nav>"
            + "<pre id=\"out\">…</pre><script>var cur='/status';"
            + "function show(p){cur=p;var x=new XMLHttpRequest();x.onload=function(){var o=document.getElementById('out');"
            + "o.textContent=x.responseText;if(cur=='/log')window.scrollTo(0,document.body.scrollHeight)};x.open('GET',p);x.send()}"
            + "setInterval(function(){if(document.getElementById('auto').checked)show(cur)},2000);show(cur)</script></body></html>";

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
