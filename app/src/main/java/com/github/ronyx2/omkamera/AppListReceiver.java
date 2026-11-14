package com.github.ronyx2.omkamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * Direct start: the camera announces with this broadcast that its app list ("Applikation") is
 * being opened. If the user wants it, OM Kamera then starts right away instead of the list.
 *
 * Way to the list: open "Applikation" within ESCAPE_MS after leaving OM Kamera (or after a
 * direct start), then the list stays.
 */
public class AppListReceiver extends BroadcastReceiver {

    static final String PREF_ON = "direct.start";
    private static final String PREF_LAST = "direct.last";
    private static final long ESCAPE_MS = 20000;

    /** Called when OM Kamera is left: opening the app list soon after shows the list */
    static void markLeft(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_ON, false)) {
            prefs.edit().putLong(PREF_LAST, SystemClock.elapsedRealtime()).commit();
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_ON, false)) {
            return;
        }
        // Uptime of the Linux side: keeps running while Android sleeps
        long now = SystemClock.elapsedRealtime();
        long last = prefs.getLong(PREF_LAST, -1);
        if (last >= 0 && now >= last && now - last < ESCAPE_MS) {
            prefs.edit().putLong(PREF_LAST, -1).commit();
            return;
        }
        prefs.edit().putLong(PREF_LAST, now).commit();
        Intent start = new Intent(context, MainActivity.class);
        start.setAction(Intent.ACTION_MAIN);
        start.addCategory(Intent.CATEGORY_LAUNCHER);
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(start);
    }
}
