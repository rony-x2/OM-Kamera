package com.github.ronyx2.omkamera;

import android.content.Context;
import android.content.Intent;

import com.sony.scalar.sysutil.didep.Gpelibrary;

/**
 * Integration with the camera system outside of Android.
 * All calls are optional: if the camera does not know them, the app keeps running.
 */
public final class SonySystem {
    private static final String ACTION_APP_INFO = "com.android.server.DAConnectionManagerService.AppInfoReceive";
    private static final String ACTION_APO = "com.android.server.DAConnectionManagerService.apo";

    private SonySystem() {
    }

    /**
     * Tells the camera that a shooting app is in the foreground.
     * Keys listed in pullingBackKeys end the app and are then handled by the camera itself.
     */
    public static void notifyAppInfo(Context context) {
        notifyAppInfo(context, false);
    }

    /**
     * resumePlay: also names the playback key as resume key (experiment: the camera may then
     * return to the app when playback is left with this key).
     */
    public static void notifyAppInfo(Context context, boolean resumePlay) {
        try {
            Intent intent = new Intent(ACTION_APP_INFO);
            intent.putExtra("package_name", context.getPackageName());
            intent.putExtra("class_name", MainActivity.class.getName());
            intent.putExtra("large_category", "CATEGORY_REC");
            intent.putExtra("small_category", "STILL");
            intent.putExtra("pullingback_key", new String[] {"KEY_PLAY", "KEY_MOVREC"});
            intent.putExtra("resume_key", resumePlay ? new String[] {"KEY_PLAY"} : new String[0]);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.error("notifyAppInfo", t);
        }
    }

    /**
     * Auto power off while the app runs: true = the camera's own power save time applies,
     * false = the camera does not go to standby.
     */
    public static void setAutoPowerOff(Context context, boolean enabled) {
        try {
            context.sendBroadcast(new Intent(ACTION_APO).putExtra("apo_info", enabled ? "APO/NORMAL" : "APO/NO"));
            Log.info("auto power off " + (enabled ? "normal" : "off"));
        } catch (Throwable t) {
            Log.error("setAutoPowerOff", t);
        }
    }

    /**
     * Uses a frame buffer format with alpha channel, so the live view shows through the overlay.
     * The camera's own shooting apps use RGBA4444.
     */
    public static void useOverlayFrameBuffer() {
        try {
            Gpelibrary.changeFrameBufferPixel(Gpelibrary.GS_FRAMEBUFFER_TYPE.RGBA4444);
        } catch (Throwable t) {
            Log.error("changeFrameBufferPixel", t);
        }
    }
}
