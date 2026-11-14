package com.github.ronyx2.omkamera;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.Callable;

/**
 * Model Context Protocol endpoint (JSON-RPC 2.0 over HTTP POST at /mcp, "Streamable HTTP" without
 * server-sent events). An AI assistant can read the camera state, change settings, focus, release
 * the shutter and look at the live view and the app's screen.
 *
 * Runs on a debug server thread. Everything that touches the camera or the UI goes through
 * {@link Host#onMain}.
 */
public class McpServer {

    public interface Host {
        CameraSession session();

        Ui ui();

        <T> T onMain(Callable<T> task);

        /** The app's screen as PNG, stretched to the panel's real aspect ratio */
        byte[] screenPng();

        String asset(String name);

        String version();

        void redraw();

        /** Warnings as shown above the exposure bar */
        java.util.List<String> warnings();

        /** Filtered acceleration x, y, z or null */
        float[] gravity();

        /** Starts or stops the selected app sequence; null if no sequence is selected */
        String sequenceRelease();

        /** Progress of a running sequence, or null */
        String sequenceStatus();
    }

    private static final String PROTOCOL = "2025-06-18";

    private final Host host;

    public McpServer(Host host) {
        this.host = host;
    }

    public DebugServer.Response handle(DebugServer.Request r) {
        Ui ui = host.ui();
        if (!ui.mcpEnabled()) {
            return DebugServer.Response.text(403, "text/plain", "MCP ist an der Kamera ausgeschaltet");
        }
        String token = r.header("authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7).trim();
        } else {
            token = r.param("k");
        }
        if (ui.mcpToken() == null || !ui.mcpToken().equals(token)) {
            return DebugServer.Response.text(401, "text/plain", "Token fehlt oder falsch");
        }
        if (!"POST".equals(r.method)) {
            // No server-sent event stream; clients then use plain request/response
            return DebugServer.Response.text(405, "text/plain", "nur POST");
        }
        JSONObject req;
        try {
            req = new JSONObject(r.body);
        } catch (JSONException e) {
            return json(error(null, -32700, "JSON nicht lesbar"));
        }
        Object id = req.opt("id");
        String method = req.optString("method", "");
        if (!req.has("id")) {
            // Notification (e.g. notifications/initialized): no answer
            return new DebugServer.Response(202, "text/plain", new byte[0]);
        }
        try {
            JSONObject result;
            if (method.equals("initialize")) {
                result = initialize(req.optJSONObject("params"));
            } else if (method.equals("ping")) {
                result = new JSONObject();
            } else if (method.equals("tools/list")) {
                result = new JSONObject().put("tools", new JSONArray(host.asset("mcp_tools.json")));
            } else if (method.equals("tools/call")) {
                JSONObject params = req.optJSONObject("params");
                String name = params != null ? params.optString("name") : "";
                JSONObject args = params != null && params.optJSONObject("arguments") != null
                        ? params.optJSONObject("arguments") : new JSONObject();
                Log.info("mcp " + name + " " + args);
                result = call(name, args);
            } else {
                return json(error(id, -32601, "Unbekannte Methode " + method));
            }
            return json(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result));
        } catch (Throwable t) {
            Log.error("mcp " + method, t);
            return json(error(id, -32603, String.valueOf(t)));
        }
    }

    private JSONObject initialize(JSONObject params) throws JSONException {
        String version = params != null ? params.optString("protocolVersion", PROTOCOL) : PROTOCOL;
        return new JSONObject()
                .put("protocolVersion", version)
                .put("capabilities", new JSONObject().put("tools", new JSONObject().put("listChanged", false)))
                .put("serverInfo", new JSONObject().put("name", "om-kamera").put("version", host.version()))
                .put("instructions", "Steuerung der Sony-Kamera über die App OM Kamera. Zuerst status aufrufen. "
                        + "Einstellungen mit einstellungen abfragen und mit einstellung_setzen ändern. "
                        + "bild zeigt Sucherbild und Oberfläche. Auslösen erzeugt echte Fotos auf der Speicherkarte.");
    }

    // ---- tools ----

    private JSONObject call(String name, final JSONObject a) throws Exception {
        final CameraSession session = host.session();
        final Ui ui = host.ui();
        if (name.equals("status")) {
            return text(host.onMain(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return status().toString(1);
                }
            }));
        }
        if (name.equals("einstellungen")) {
            return text(host.onMain(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return settings(a.optString("gruppe", null), a.optBoolean("alle", false)).toString(1);
                }
            }));
        }
        if (name.equals("einstellung_setzen")) {
            return text(host.onMain(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return set(a.optString("id"), a.optString("wert"));
                }
            }));
        }
        if (name.equals("belichtung")) {
            final String was = a.optString("was");
            final int steps = Math.max(-10, Math.min(10, a.optInt("schritte", 0)));
            if (!was.equals("zeit") && !was.equals("blende") && !was.equals("shift") && !was.equals("ev")) {
                return text("unbekannt: " + was);
            }
            final int dir = steps < 0 ? -1 : 1;
            // One detent at a time, waiting for the camera to report the new value:
            // quick successive calls are merged by the camera and steps get lost
            for (int i = 0; i < Math.abs(steps); i++) {
                final String before = host.onMain(new Callable<String>() {
                    @Override
                    public String call() {
                        String v = session.shutterText() + " " + session.apertureText();
                        if (was.equals("zeit")) {
                            session.adjustShutterSpeed(dir);
                        } else if (was.equals("blende")) {
                            session.adjustAperture(dir);
                        } else if (was.equals("shift")) {
                            session.adjustProgramShift(dir);
                        } else {
                            ui.stepSetting(Settings.get("ev"), dir);
                        }
                        return v;
                    }
                });
                if (was.equals("zeit") || was.equals("blende") || was.equals("shift")) {
                    for (int w = 0; w < 10; w++) {
                        try {
                            Thread.sleep(80);
                        } catch (InterruptedException e) {
                            break;
                        }
                        String now = host.onMain(new Callable<String>() {
                            @Override
                            public String call() {
                                return session.shutterText() + " " + session.apertureText();
                            }
                        });
                        if (now != null && !now.equals(before)) {
                            break;
                        }
                    }
                }
            }
            host.onMain(new Callable<String>() {
                @Override
                public String call() {
                    host.redraw();
                    return null;
                }
            });
            return text("ok" + exposureLine());
        }
        if (name.equals("fokus")) {
            String res = focus();
            if (!a.optBoolean("halten", false)) {
                release();
            }
            return text("Fokus: " + res);
        }
        if (name.equals("fokus_loesen")) {
            release();
            return text("ok");
        }
        if (name.equals("ausloesen")) {
            String seq = host.onMain(new Callable<String>() {
                @Override
                public String call() {
                    return host.sequenceRelease();
                }
            });
            if (seq != null) {
                return text(seq);
            }
            if (session.isDirectShutter()) {
                return error("Auslöser-Steuerung steht auf „Kamera direkt“, auslösen geht nur mit der App-Steuerung");
            }
            String res = focus();
            host.onMain(new Callable<Object>() {
                @Override
                public Object call() {
                    session.capture(false);
                    return null;
                }
            });
            Thread.sleep(400);
            if (session.selfTimerRunning()) {
                return text("Selbstauslöser läuft, Aufnahme in " + session.selfTimerRemaining() + " s (Fokus vorher: " + res + ")");
            }
            Thread.sleep(800);
            release();
            return text("ausgelöst, Fokus vorher: " + res);
        }
        if (name.equals("taste")) {
            final Keys.Key key;
            try {
                key = Keys.Key.valueOf(a.optString("name"));
            } catch (IllegalArgumentException e) {
                return error("unbekannte Taste " + a.optString("name"));
            }
            if (key == Keys.Key.SHUTTER_HALF || key == Keys.Key.SHUTTER_FULL || key == Keys.Key.AEL) {
                return error("dafür gibt es fokus, ausloesen und ael");
            }
            final int n = Math.max(1, Math.min(20, a.optInt("anzahl", 1)));
            return text(host.onMain(new Callable<String>() {
                @Override
                public String call() {
                    for (int i = 0; i < n; i++) {
                        ui.onKey(key);
                    }
                    return "ok, Zustand " + ui.state().name()
                            + (ui.state() == Ui.State.MENU ? " Reiter " + Ui.TABS[ui.tab()] + " Zeile " + ui.row() + " " + ui.focus().name() : "")
                            + (ui.state() == Ui.State.FN || ui.state() == Ui.State.FN_EDIT ? " Feld " + ui.fnIndex() : "");
                }
            }));
        }
        if (name.equals("fokusfeld")) {
            return text(host.onMain(new Callable<String>() {
                @Override
                public String call() {
                    CameraSession.Result res;
                    if (a.optBoolean("mitte", false)) {
                        res = session.centerFocusPoint();
                    } else if (a.has("x") && a.has("y")) {
                        res = session.setFocusPoint(a.optInt("x"), a.optInt("y"));
                    } else {
                        res = session.moveFocusPoint(a.optInt("dx", 0), a.optInt("dy", 0));
                    }
                    host.redraw();
                    int[] fp = session.focusPoint();
                    return (res.ok ? "ok" : "gesperrt (Grund " + res.inhibition + ")")
                            + (fp != null ? ", Spot bei " + fp[0] + "," + fp[1] : "");
                }
            }));
        }
        if (name.equals("ael")) {
            final boolean on = a.optBoolean("an", false);
            return text(host.onMain(new Callable<String>() {
                @Override
                public String call() {
                    ui.onAel(on);
                    return ui.aeLocked() ? "Belichtung gespeichert" : "Belichtung frei";
                }
            }));
        }
        if (name.equals("bild")) {
            return pictures(a.optString("quelle", "beides"));
        }
        if (name.equals("parameter")) {
            return text(host.onMain(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return rawParameters(a.optString("filter", ""), a.optJSONObject("setzen"));
                }
            }));
        }
        if (name.equals("verarbeiten")) {
            Processor pr = host.ui().processor();
            if (pr == null) {
                return error("Verarbeitung nicht verfügbar");
            }
            String art = a.optString("art", "status").toLowerCase(java.util.Locale.US);
            if ("status".equals(art)) {
                return text(pr.status());
            }
            if ("abbrechen".equals(art)) {
                pr.cancelAll();
                return text("abgebrochen");
            }
            Processor.Kind kind;
            if ("dng".equals(art)) {
                kind = Processor.Kind.DNG;
            } else if ("tiff".equals(art) || "tif".equals(art)) {
                kind = Processor.Kind.TIFF;
            } else if ("png".equals(art)) {
                kind = Processor.Kind.PNG;
            } else if ("heic".equals(art)) {
                kind = Processor.Kind.HEIC;
            } else if ("avif".equals(art)) {
                kind = Processor.Kind.AVIF;
            } else if ("mittel".equals(art)) {
                kind = Processor.Kind.MEAN;
            } else if ("summe".equals(art)) {
                kind = Processor.Kind.SUM;
            } else if ("maximum".equals(art)) {
                kind = Processor.Kind.MAX;
            } else if ("fokus".equals(art)) {
                kind = Processor.Kind.FOCUS;
            } else {
                return error("unbekannte Art " + art);
            }
            java.util.List<java.io.File> files;
            String datei = a.optString("datei", "");
            if (datei.length() > 0 && !kind.combines()) {
                java.io.File f = Processor.find(datei);
                if (f == null) {
                    return error("Datei " + datei + " nicht gefunden");
                }
                files = java.util.Collections.singletonList(f);
            } else {
                files = Processor.newest(!kind.combines() ? 1 : Math.max(2, a.optInt("anzahl", 3)));
            }
            if (kind == Processor.Kind.PNG && a.has("bit")) {
                host.ui().setPngBits(a.optInt("bit", 16));
            }
            if (kind == Processor.Kind.HEIC || kind == Processor.Kind.AVIF) {
                int size = a.optInt("mp", 0);
                host.ui().setHeif(a.optInt("qualitaet", 0), size == 24 ? 0 : size == 12 ? 1 : size == 6 ? 2 : -1,
                        a.optInt("kerne", 0));
            }
            return text(pr.add(kind, files));
        }
        if (name.equals("zeitraffer")) {
            final String act = a.optString("aktion", "status");
            if ("setzen".equals(act)) {
                // Time-lapse options like in the menu (values as stored there)
                final String key = a.optString("schluessel");
                final int val = a.optInt("wert", Integer.MIN_VALUE);
                java.util.List<String> keys = java.util.Arrays.asList("interval", "count", "bulb", "at", "ath", "atm",
                        "delay", "win", "wfrom", "wto", "wdays", "hg", "isomax", "isomin", "film", "rampto", "rampmin");
                if (!keys.contains(key) || val == Integer.MIN_VALUE) {
                    return text("schluessel einer von " + keys + ", wert als Zahl (interval in Zehntelsekunden, "
                            + "at 0/1 = Verzögerung/Uhrzeit, win 0/1, wfrom/wto Stunde, wdays Bitmaske Mo=1, bulb Sekunden, hg 0/1/-1)");
                }
                return text(host.onMain(new Callable<String>() {
                    @Override
                    public String call() {
                        host.ui().sequencer().put("tl." + key, val);
                        host.redraw();
                        return "tl." + key + " = " + val;
                    }
                }));
            }
            final double ziel = a.optDouble("ziel", 15);
            final int minuten = a.optInt("minuten", 20);
            return text(host.onMain(new Callable<String>() {
                @Override
                public String call() {
                    Sequencer q = host.ui().sequencer();
                    if ("rampe".equals(act)) {
                        return q.tlRamp(ziel, minuten);
                    }
                    if ("rampe_stopp".equals(act)) {
                        return q.tlRampStop();
                    }
                    if ("heller".equals(act)) {
                        return q.tlReference(1);
                    }
                    if ("dunkler".equals(act)) {
                        return q.tlReference(-1);
                    }
                    String st = q.status();
                    return st != null ? st : "kein Ablauf aktiv (Modus: " + q.mode().label + ")";
                }
            }));
        }
        if (name.equals("uhr")) {
            CameraClock c = ui.clock();
            if (c == null) {
                return text("Uhr nicht verfügbar");
            }
            String act = a.optString("aktion", "status");
            if ("ntp".equals(act)) {
                c.syncNtp(true);
                // Wait for the background query (at most a few seconds)
                for (int i = 0; i < 80 && c.busy(); i++) {
                    Thread.sleep(100);
                }
                return text(c.state() + "\nUhrzeit: " + CameraClock.describe() + "\nSony-Uhr: " + CameraClock.describeCamera());
            }
            if ("setzen".equals(act)) {
                long ms = a.optLong("zeit_ms", 0);
                if (ms < 1500000000000L) {
                    return text("zeit_ms fehlt (Millisekunden seit 1970, UTC)");
                }
                return text(c.syncFrom(ms, "vom PC") + "\nUhrzeit: " + CameraClock.describe() + "\nSony-Uhr: " + CameraClock.describeCamera());
            }
            return text("Uhrzeit: " + CameraClock.describe() + "\nSony-Uhr: " + CameraClock.describeCamera() + "\nAbgleich: " + c.state()
                    + "\nautomatisch: " + (c.auto() ? "ein" : "aus") + ", Zeitserver: " + CameraClock.SERVERS[c.server()]
                    + ", Sommerzeit automatisch: " + (c.autoSummer() ? "ein" : "aus"));
        }
        if (name.equals("rawtest")) {
            final String mode = a.optString("modus", "status");
            final int n = a.optInt("anzahl", 2);
            return text(host.onMain(new Callable<String>() {
                @Override
                public String call() {
                    RawLab lab = host.session().rawLab();
                    if ("status".equals(mode)) {
                        return lab.result();
                    }
                    return lab.start(mode, n);
                }
            }));
        }
        if (name.equals("jpeg_test")) {
            return text(JpegProbe.run(a.optString("datei", null), a.optJSONArray("stufen"), a.optBoolean("loeschen", true)));
        }
        if (name.equals("dateien")) {
            return text(listFiles(a.optString("ordner", "OMKAMERA")));
        }
        if (name.equals("log")) {
            int lines = Math.max(1, Math.min(400, a.optInt("zeilen", 60)));
            String[] all = Log.tail(64 * 1024).split("\n");
            StringBuilder b = new StringBuilder();
            for (int i = Math.max(0, all.length - lines); i < all.length; i++) {
                b.append(all[i]).append('\n');
            }
            return text(b.toString());
        }
        return error("unbekanntes Werkzeug " + name);
    }

    /** Files in a folder on the card (OMKAMERA, DCIM or a DCIM sub folder), newest last */
    private static String listFiles(String folder) {
        java.io.File root = android.os.Environment.getExternalStorageDirectory();
        java.io.File dir = new java.io.File(root, folder.replace("..", ""));
        if ("DCIM".equalsIgnoreCase(folder)) {
            // The newest picture folder
            java.io.File[] subs = dir.listFiles();
            java.io.File newest = null;
            if (subs != null) {
                for (java.io.File f : subs) {
                    if (f.isDirectory() && (newest == null || f.getName().compareTo(newest.getName()) > 0)) {
                        newest = f;
                    }
                }
            }
            if (newest != null) {
                dir = newest;
            }
        }
        java.io.File[] files = dir.listFiles();
        if (files == null) {
            return "Ordner " + dir.getPath() + " nicht gefunden";
        }
        java.util.Arrays.sort(files, new java.util.Comparator<java.io.File>() {
            @Override
            public int compare(java.io.File x, java.io.File y) {
                return x.getName().compareTo(y.getName());
            }
        });
        StringBuilder b = new StringBuilder(dir.getPath()).append(" (").append(files.length).append(" Dateien)\n");
        for (int i = Math.max(0, files.length - 40); i < files.length; i++) {
            b.append(files[i].getName()).append("  ").append(files[i].isDirectory() ? "Ordner" : files[i].length() < 1024 ? files[i].length() + " Bytes" : files[i].length() / 1024 + " KB").append('\n');
        }
        return b.toString();
    }

    /** Half press, waits up to 3 s for the result */
    private String focus() throws InterruptedException {
        final CameraSession session = host.session();
        final Ui ui = host.ui();
        String mode = host.onMain(new Callable<String>() {
            @Override
            public String call() {
                return Settings.get("afmf").safeGet(session.current());
            }
        });
        if ("manual".equals(mode)) {
            return "MF (manueller Fokus)";
        }
        host.onMain(new Callable<Object>() {
            @Override
            public Object call() {
                session.startFocus();
                return null;
            }
        });
        long until = System.currentTimeMillis() + 3000;
        Ui.FocusResult res = Ui.FocusResult.NONE;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(100);
            res = ui.focusResult();
            if (res != Ui.FocusResult.NONE) {
                break;
            }
        }
        return res.name();
    }

    private void release() {
        final CameraSession session = host.session();
        final Ui ui = host.ui();
        host.onMain(new Callable<Object>() {
            @Override
            public Object call() {
                session.cancelFocus();
                ui.onFocusReleased();
                return null;
            }
        });
    }

    private String exposureLine() {
        return host.onMain(new Callable<String>() {
            @Override
            public String call() {
                CameraSession s = host.session();
                CameraSession.Params p = s.current();
                return "\n" + host.ui().modeName() + "  " + s.shutterText() + "  " + s.apertureText()
                        + "  EV " + Settings.get("ev").currentText(p) + "  ISO " + Settings.get("iso").currentText(p);
            }
        });
    }

    /** Reads and optionally writes raw camera parameters, for experiments */
    private String rawParameters(String filter, JSONObject set) throws JSONException {
        CameraSession s = host.session();
        StringBuilder out = new StringBuilder();
        if (set != null && set.length() > 0) {
            CameraSession.Params edit = s.createEdit();
            java.util.Iterator<?> keys = set.keys();
            while (keys.hasNext()) {
                String k = String.valueOf(keys.next());
                edit.params.set(k, set.getString(k));
            }
            CameraSession.Result r = s.apply(edit);
            out.append(r.ok ? "angenommen" : "abgelehnt (Grund " + r.inhibition + ", " + r.error + ")").append('\n');
            host.redraw();
            if (filter.length() == 0) {
                // Show the keys that were written when no filter is given
                java.util.Iterator<?> again = set.keys();
                while (again.hasNext()) {
                    String k = String.valueOf(again.next());
                    out.append(k).append('=').append(s.current().params.get(k)).append('\n');
                }
                return out.toString();
            }
        }
        String flat = s.flatten();
        if (flat != null) {
            String[] pairs = flat.split(";");
            java.util.Arrays.sort(pairs);
            for (String pair : pairs) {
                if (filter.length() == 0 || pair.contains(filter)) {
                    out.append(pair).append('\n');
                }
            }
        }
        return out.toString();
    }

    private JSONObject status() throws JSONException {
        CameraSession s = host.session();
        Ui ui = host.ui();
        CameraSession.Params p = s.current();
        JSONObject o = new JSONObject();
        o.put("modell", com.github.ma1co.openmemories.framework.DeviceInfo.getInstance().getModel());
        o.put("app", host.version());
        o.put("modus", ui.modeName());
        o.put("zeit", s.shutterText());
        o.put("blende", s.apertureText());
        o.put("ev", Settings.get("ev").currentText(p));
        o.put("iso", Settings.get("iso").currentText(p));
        o.put("akku", ui.batteryLevel());
        o.put("laedt", ui.batteryPlugged());
        o.put("schreibt", ui.writing());
        o.put("fokus", ui.focusResult().name());
        o.put("ael", ui.aeLocked());
        o.put("bedienung", ui.state().name());
        o.put("ausloeser", s.isDirectShutter() ? "Kamera direkt" : "App");
        int[] fp = s.focusPoint();
        if (fp != null) {
            o.put("spot", fp[0] + "," + fp[1]);
        }
        o.put("restaufnahmen", ui.remainingShots());
        String seq = host.sequenceStatus();
        if (seq != null) {
            o.put("ablauf", seq);
        }
        o.put("warnungen", new JSONArray(host.warnings()));
        if (!Float.isNaN(ui.roll())) {
            o.put("neigung", String.format(java.util.Locale.US, "roll %.1f° pitch %.1f°", ui.roll(), ui.pitch()));
        }
        float[] g = host.gravity();
        if (g != null) {
            o.put("beschleunigung", String.format(java.util.Locale.US, "x %.2f y %.2f z %.2f", g[0], g[1], g[2]));
        }
        short[][] h = s.histogram();
        o.put("histogramm", h != null ? h[0].length + " Stufen" : "keins");
        o.put("brennweite", Math.round(s.focalLength()));
        o.put("uhr", CameraClock.describe());
        o.put("sony_uhr", CameraClock.describeCamera());
        AutoIso ai = host.ui().autoIso();
        if (ai != null && ai.mode() != 0) {
            o.put("iso_automatik", AutoIso.LABELS[ai.mode()] + ", " + ai.state());
        }
        java.util.Map<String, String> hw = s.hardwareProbe();
        if (hw != null) {
            o.put("rechenwerk", new JSONObject(hw));
        }
        String lens = s.lensText();
        if (lens != null) {
            o.put("objektiv", lens);
        }
        JSONObject values = new JSONObject();
        for (Setting st : Settings.all()) {
            if (st.isShown(p)) {
                values.put(st.id, st.currentText(p));
            }
        }
        o.put("einstellungen", values);
        return o;
    }

    private JSONArray settings(String group, boolean all) throws JSONException {
        CameraSession.Params p = host.session().current();
        JSONArray list = new JSONArray();
        for (Setting s : Settings.all()) {
            if (group != null && group.length() > 0 && !s.category.title.equalsIgnoreCase(group)) {
                continue;
            }
            boolean shown = s.isShown(p);
            if (!all && !shown) {
                continue;
            }
            JSONObject j = new JSONObject();
            j.put("id", s.id);
            j.put("name", s.label);
            j.put("gruppe", s.category.title);
            if (all) {
                j.put("sichtbar", shown);
            }
            if (s.isAvailable(p)) {
                j.put("wert", s.safeGet(p));
                j.put("anzeige", s.currentText(p));
                JSONArray values = new JSONArray();
                for (String v : s.safeValues(p)) {
                    values.put(v + "=" + s.display(p, v));
                }
                j.put("werte", values);
            } else {
                j.put("verfuegbar", false);
            }
            list.put(j);
        }
        return list;
    }

    private String set(String id, String value) {
        Setting s = Settings.get(id);
        if (s == null) {
            return "Unbekannte Einstellung " + id;
        }
        CameraSession.Params p = host.session().current();
        if (!s.isAvailable(p)) {
            return s.label + " ist gerade nicht verfügbar";
        }
        // Accept the internal value as well as the text shown on the camera
        String target = null;
        for (String v : s.safeValues(p)) {
            if (v.equals(value)) {
                target = v;
                break;
            }
        }
        if (target == null) {
            for (String v : s.safeValues(p)) {
                if (s.display(p, v).equalsIgnoreCase(value.trim())) {
                    target = v;
                    break;
                }
            }
        }
        if (target == null) {
            return "Wert " + value + " gibt es für " + s.label + " nicht";
        }
        CameraSession.Result r = s.write(host.session(), target);
        host.redraw();
        String now = s.currentText(host.session().current());
        if (!r.ok) {
            return "gesperrt" + (r.inhibition != 0 ? " (Grund " + r.inhibition + ")" : "") + ", Wert bleibt " + now;
        }
        String wanted = s.display(p, target);
        return now.equals(wanted) ? "ok, " + s.label + ": " + now
                : "angenommen, aber die Kamera meldet " + now + " statt " + wanted;
    }

    private JSONObject pictures(String source) throws JSONException {
        JSONArray content = new JSONArray();
        boolean live = !source.equals("anzeige");
        boolean screen = !source.equals("live");
        if (live) {
            byte[] frame = null;
            // The live view sequence starts on the first request; give it a moment
            for (int i = 0; i < 5 && frame == null; i++) {
                frame = host.session().liveFrame();
                if (frame == null) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            if (frame != null) {
                content.put(image(frame, "image/jpeg"));
            } else {
                content.put(new JSONObject().put("type", "text").put("text", "Kein Livebild verfügbar"));
            }
        }
        if (screen) {
            byte[] png = host.onMain(new Callable<byte[]>() {
                @Override
                public byte[] call() {
                    return host.screenPng();
                }
            });
            if (png != null) {
                content.put(image(png, "image/png"));
            } else {
                content.put(new JSONObject().put("type", "text").put("text", "Keine Anzeige verfügbar"));
            }
        }
        return new JSONObject().put("content", content);
    }

    // ---- helpers ----

    private static JSONObject image(byte[] data, String mime) throws JSONException {
        return new JSONObject().put("type", "image").put("mimeType", mime)
                .put("data", Base64.encodeToString(data, Base64.NO_WRAP));
    }

    private static JSONObject text(String s) throws JSONException {
        return new JSONObject().put("content", new JSONArray().put(
                new JSONObject().put("type", "text").put("text", s != null ? s : "Zeitüberschreitung")));
    }

    private static JSONObject error(String s) throws JSONException {
        return text(s).put("isError", true);
    }

    private static JSONObject error(Object id, int code, String message) {
        try {
            return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id)
                    .put("error", new JSONObject().put("code", code).put("message", message));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static DebugServer.Response json(JSONObject o) {
        return DebugServer.Response.text(200, "application/json", o.toString());
    }
}
