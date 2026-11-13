package com.github.ronyx2.omkamera;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.info("onResume");
        SonySystem.notifyAppInfo(this);
        SonySystem.setNormalAutoPowerOff(this);
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
            session.startPreview(holder);
        }
        redraw();
    }

    @Override
    protected void onPause() {
        Log.info("onPause");
        session.close();
        focusBeep.release();
        super.onPause();
    }

    // ---- surface ----

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (session.isOpen()) {
            session.startPreview(h);
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
        // Every key with its codes, to learn the layout of each camera model
        Log.info("key " + key + " scan=" + event.getScanCode() + " code=" + keyCode
                + (event.getRepeatCount() > 0 ? " repeat=" + event.getRepeatCount() : ""));
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
        if (key == Keys.Key.SHUTTER_HALF || key == Keys.Key.SHUTTER_FULL || key == Keys.Key.OTHER) {
            return super.onKeyUp(keyCode, event);
        }
        // Key up of keys handled on key down must not reach the system (e.g. MENU would close the app)
        return true;
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

    @Override
    public void onFocus(int status, int[] indices) {
        Log.info("focus status=" + status + " frames=" + java.util.Arrays.toString(indices));
        ui.onFocus(status, indices);
    }

    @Override
    public void playFocusBeep() {
        focusBeep.play();
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
