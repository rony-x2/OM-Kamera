package com.github.ronyx2.omkamera;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * Shows whether the camera is still writing pictures to the card.
 * Newer platforms (V6, e.g. ILCE-7M2) report each stored file; older ones (ILCE-6000)
 * do not, so the newest DCIM folder is watched until new files appear and stop growing.
 */
public class WriteMonitor {
    public interface Listener {
        void onWritingChanged(boolean writing);
    }

    private static final long POLL_MS = 300;
    private static final long TIMEOUT_MS = 60000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    /** Shots not yet confirmed as written */
    private int pending;
    private int baseCount;
    private long lastSize = -1;
    private int stableRounds;
    private long startedAt;
    private File folder;

    public WriteMonitor(Listener listener) {
        this.listener = listener;
    }

    public boolean isWriting() {
        return pending > 0;
    }

    /** Called after each exposure. */
    public void onShot() {
        if (pending == 0) {
            folder = newestFolder();
            baseCount = count(folder);
            lastSize = -1;
            stableRounds = 0;
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, POLL_MS);
            listener.onWritingChanged(true);
        }
        pending++;
        startedAt = System.currentTimeMillis();
    }

    /** Called when the camera reports a stored file (platform V6 and newer). */
    public void onStored() {
        if (pending > 0) {
            pending--;
            if (pending == 0) {
                finish();
            }
        }
    }

    public void reset() {
        handler.removeCallbacks(poll);
        if (pending > 0) {
            pending = 0;
            listener.onWritingChanged(false);
        }
    }

    private void finish() {
        handler.removeCallbacks(poll);
        pending = 0;
        listener.onWritingChanged(false);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (pending == 0) {
                return;
            }
            if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
                Log.info("write monitor timeout");
                finish();
                return;
            }
            File f = newestFolder();
            if (folder == null || (f != null && !f.equals(folder))) {
                // A new folder was started for this shot
                folder = f;
                baseCount = 0;
            }
            int n = count(folder);
            long size = size(folder);
            if (n >= baseCount + pending && size == lastSize) {
                stableRounds++;
            } else {
                stableRounds = 0;
            }
            lastSize = size;
            if (stableRounds >= 2) {
                Log.info("write done files=" + n);
                finish();
                return;
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private static File newestFolder() {
        try {
            File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
            File[] dirs = dcim.listFiles();
            File best = null;
            if (dirs != null) {
                for (File d : dirs) {
                    if (d.isDirectory() && (best == null || d.getName().compareTo(best.getName()) > 0)) {
                        best = d;
                    }
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int count(File dir) {
        File[] files = dir != null ? dir.listFiles() : null;
        return files != null ? files.length : 0;
    }

    private static long size(File dir) {
        File[] files = dir != null ? dir.listFiles() : null;
        long s = 0;
        if (files != null) {
            for (File f : files) {
                s += f.length();
            }
        }
        return s;
    }
}
