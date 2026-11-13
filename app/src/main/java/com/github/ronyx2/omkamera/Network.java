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

    /**
     * SSID, standard, speed, signal and IP of the connection, or null without connection.
     * The standard is estimated from the link speed: the camera's WLAN chip works at 2.4 GHz
     * with 802.11b/g/n, newer standards are not possible.
     */
    public String[] details() {
        try {
            WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
            if (info == null || info.getIpAddress() == 0) {
                return null;
            }
            int mbit = info.getLinkSpeed();
            String standard = mbit <= 0 ? "unbekannt"
                    : mbit <= 11 ? "802.11b (Wi-Fi 1)"
                    : mbit <= 54 ? "802.11g (Wi-Fi 3) oder n bei schwachem Signal"
                    : "802.11n (Wi-Fi 4)";
            int rssi = info.getRssi();
            int bars = WifiManager.calculateSignalLevel(rssi, 5);
            String ssid = info.getSSID();
            if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return new String[]{
                ssid != null ? ssid : "–",
                standard,
                mbit > 0 ? mbit + " Mbit/s" : "–",
                (bars == 4 ? "sehr gut" : bars == 3 ? "gut" : bars == 2 ? "mittel" : "schwach") + " (" + rssi + " dBm)",
                ip()
            };
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
