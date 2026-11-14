package com.github.ronyx2.omkamera;

import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Logs to logcat and to a file on the memory card.
 * The file is the easiest way to see what happened on a camera without adb.
 * The camera's card file system only accepts 8.3 file names.
 */
public final class Log {
    private static final String TAG = "OMKamera";
    private static final String FILE_NAME = "OMKAMLOG.TXT";
    private static final long MAX_SIZE = 512 * 1024;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static File file;

    private Log() {
    }

    public static synchronized void init() {
        try {
            file = new File(Environment.getExternalStorageDirectory(), FILE_NAME);
            if (file.exists() && file.length() > MAX_SIZE) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Throwable t) {
            file = null;
        }
        info("---- start ----");
    }

    /** Last lines of the log, for the debug server. */
    public static synchronized String tail(int maxBytes) {
        if (file == null || !file.exists()) {
            return "";
        }
        java.io.RandomAccessFile f = null;
        try {
            f = new java.io.RandomAccessFile(file, "r");
            long start = Math.max(0, f.length() - maxBytes);
            byte[] b = new byte[(int) (f.length() - start)];
            f.seek(start);
            f.readFully(b);
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return "Log nicht lesbar: " + t;
        } finally {
            if (f != null) {
                try {
                    f.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void info(String msg) {
        android.util.Log.i(TAG, msg);
        write("I", msg);
    }

    public static void error(String msg, Throwable t) {
        android.util.Log.e(TAG, msg, t);
        StringWriter sw = new StringWriter();
        if (t != null) {
            t.printStackTrace(new PrintWriter(sw));
        }
        write("E", msg + (t != null ? "\n" + sw : ""));
    }

    private static synchronized void write(String level, String msg) {
        if (file == null) {
            return;
        }
        FileWriter w = null;
        try {
            w = new FileWriter(file, true);
            // Wall time from the camera's own clock (Android's clock is not set on the camera)
            TIME.setTimeZone(CameraClock.zone());
            w.write(TIME.format(new Date(CameraClock.nowUtc())) + " " + level + " " + msg + "\r\n");
        } catch (Throwable ignored) {
            // The card may be missing or full; logging must never break the app
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
