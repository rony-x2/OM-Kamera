package com.github.ronyx2.omkamera;

import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Iterator;
import java.util.Map;

/**
 * Saved shooting settings, like the camera's "KameraEinstSpei" (memories 1-3) and
 * "Einstlg. speich./lad." (everything to a file on the card and back).
 */
public class Memories {
    public static final int SLOTS = 3;
    private static final String DIR = "OMKAMERA";
    private static final String FILE = "EINSTELL.JSN";

    private final CameraSession session;
    private final SharedPreferences prefs;

    public Memories(CameraSession session, SharedPreferences prefs) {
        this.session = session;
        this.prefs = prefs;
    }

    /** Current values of all settings the camera has */
    private JSONObject snapshot() {
        JSONObject o = new JSONObject();
        CameraSession.Params p = session.current();
        for (Setting s : Settings.all()) {
            if (s.isAvailable(p)) {
                try {
                    o.put(s.id, s.safeGet(p));
                } catch (Exception ignored) {
                }
            }
        }
        return o;
    }

    /** Applies saved values in the order of the settings list; returns how many failed */
    private int apply(JSONObject values) {
        int failed = 0;
        for (Setting s : Settings.all()) {
            String v = values.optString(s.id, null);
            if (v == null) {
                continue;
            }
            CameraSession.Params p = session.current();
            if (!s.isAvailable(p) || v.equals(s.safeGet(p)) || !s.safeValues(p).contains(v)) {
                continue;
            }
            if (!s.write(session, v).ok) {
                failed++;
            }
        }
        return failed;
    }

    public boolean has(int slot) {
        return prefs.getString("mem." + slot, null) != null;
    }

    public String describe(int slot) {
        String raw = prefs.getString("mem." + slot, null);
        if (raw == null) {
            return "leer";
        }
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject v = o.getJSONObject("values");
            Setting q = Settings.get("quality");
            Setting d = Settings.get("drive");
            String qText = v.has("quality") ? q.display(v.getString("quality")) : "";
            String dText = v.has("drive") ? d.display(v.getString("drive")) : "";
            return o.optString("time", "") + "  ·  " + qText + (dText.length() > 0 ? ", " + dText : "");
        } catch (Exception e) {
            return "gesichert";
        }
    }

    public int save(int slot) {
        JSONObject values = snapshot();
        try {
            JSONObject o = new JSONObject();
            o.put("time", new java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(new java.util.Date()));
            o.put("values", values);
            prefs.edit().putString("mem." + slot, o.toString()).apply();
        } catch (Exception e) {
            Log.error("memory save", e);
        }
        Log.info("memory " + slot + " saved: " + values.length());
        return values.length();
    }

    public String load(int slot) {
        String raw = prefs.getString("mem." + slot, null);
        if (raw == null) {
            return "Speicher " + slot + " ist leer";
        }
        try {
            int failed = apply(new JSONObject(raw).getJSONObject("values"));
            return failed == 0 ? "Speicher " + slot + " geladen" : "Speicher " + slot + " geladen, " + failed + " Werte nicht übernommen";
        } catch (Exception e) {
            Log.error("memory load", e);
            return "Speicher " + slot + " nicht lesbar";
        }
    }

    // ---- file on the card ----

    private File file() {
        return new File(new File(Environment.getExternalStorageDirectory(), DIR), FILE);
    }

    /** Current settings, the memories and all app options into one file */
    public String exportToCard() {
        try {
            JSONObject o = new JSONObject();
            o.put("app", BuildConfig.VERSION_NAME);
            o.put("current", snapshot());
            JSONObject app = new JSONObject();
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                app.put(e.getKey(), e.getValue());
            }
            o.put("options", app);
            File f = file();
            f.getParentFile().mkdirs();
            // The camera's card file system cannot truncate files: delete before writing
            f.delete();
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(o.toString(1).getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return "Gespeichert: " + DIR + "/" + FILE;
        } catch (Exception e) {
            Log.error("export", e);
            return "Speichern nicht möglich: " + e.getMessage();
        }
    }

    public String importFromCard() {
        File f = file();
        if (!f.isFile()) {
            return "Keine Datei " + DIR + "/" + FILE + " auf der Karte";
        }
        try {
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            } finally {
                in.close();
            }
            JSONObject o = new JSONObject(new String(buf, "UTF-8"));
            JSONObject app = o.optJSONObject("options");
            if (app != null) {
                SharedPreferences.Editor e = prefs.edit();
                Iterator<?> keys = app.keys();
                while (keys.hasNext()) {
                    String k = String.valueOf(keys.next());
                    Object v = app.get(k);
                    if (v instanceof Boolean) {
                        e.putBoolean(k, (Boolean) v);
                    } else if (v instanceof Integer) {
                        e.putInt(k, (Integer) v);
                    } else if (v instanceof Long) {
                        e.putLong(k, (Long) v);
                    } else if (v instanceof Number) {
                        e.putFloat(k, ((Number) v).floatValue());
                    } else {
                        e.putString(k, String.valueOf(v));
                    }
                }
                e.apply();
            }
            int failed = o.has("current") ? apply(o.getJSONObject("current")) : 0;
            return "Geladen" + (failed > 0 ? ", " + failed + " Werte nicht übernommen" : "") + " (App-Optionen nach Neustart)";
        } catch (Exception e) {
            Log.error("import", e);
            return "Datei nicht lesbar: " + e.getMessage();
        }
    }
}
