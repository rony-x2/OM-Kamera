package com.github.ronyx2.omkamera;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

/**
 * WLAN while the app runs. The camera connects to the access point stored in its own
 * settings (Network → Access Point Set.). Outside the app the camera controls WLAN itself.
 */
public class Network {
    private final WifiManager wifi;

    public Network(Context context) {
        wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    public void setEnabled(boolean enabled) {
        try {
            if (wifi != null && wifi.isWifiEnabled() != enabled) {
                Log.info("wifi " + (enabled ? "on" : "off"));
                wifi.setWifiEnabled(enabled);
            }
        } catch (Throwable t) {
            Log.error("setWifiEnabled", t);
        }
    }

    /** IP address in the WLAN, or null if not connected. */
    public String ip() {
        try {
            WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
            int a = info != null ? info.getIpAddress() : 0;
            if (a == 0) {
                return null;
            }
            return (a & 0xff) + "." + ((a >> 8) & 0xff) + "." + ((a >> 16) & 0xff) + "." + ((a >> 24) & 0xff);
        } catch (Throwable t) {
            return null;
        }
    }

    public String status() {
        try {
            if (wifi == null) {
                return "nicht verfügbar";
            }
            switch (wifi.getWifiState()) {
                case WifiManager.WIFI_STATE_ENABLING: return "wird eingeschaltet";
                case WifiManager.WIFI_STATE_DISABLED: return "aus";
                case WifiManager.WIFI_STATE_DISABLING: return "wird ausgeschaltet";
                case WifiManager.WIFI_STATE_ENABLED: {
                    String ip = ip();
                    if (ip != null) {
                        return ip;
                    }
                    return "verbindet …";
                }
                default: return "unbekannt";
            }
        } catch (Throwable t) {
            return "Fehler";
        }
    }
}
