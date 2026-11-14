package com.github.ronyx2.omkamera;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Read-only diagnosis with root rights on the camera's Linux host (outside Android).
 *
 * Only the fixed queries below exist. There is deliberately no way to pass a command from the
 * network, the MCP server or the debug page: callers choose a query by name.
 *
 * The query is written as a script into the app's own folder; the host runs it as root (via
 * librootshell / libosal_uipc) and writes the output next to it, where the app reads it.
 */
public final class RootShell {

    /** Name, title and script of each query. Scripts run on the host with /bin/sh (busybox). */
    private static final String[][] QUERIES = {
        {"system", "System",
            "uname -a\necho\necho Laufzeit/Leerlauf s: $(cat /proc/uptime)\necho Last: $(cat /proc/loadavg)\necho\n"
            + "grep -E 'MemTotal|MemFree|^Buffers|^Cached|AnonPages|Slab:' /proc/meminfo\necho\n"
            + "for c in 0 1 2 3; do f=/sys/devices/system/cpu/cpu$c; "
            + "echo cpu$c online=$(cat $f/online 2>/dev/null) takt=$(cat $f/cpufreq/scaling_cur_freq 2>/dev/null); done\n"},
        {"speicher", "Arbeitsspeicher", "cat /proc/meminfo\n"},
        {"prozesse", "Prozesse (Speicher in kB)",
            "for p in /proc/[0-9]*; do r=$(grep VmRSS $p/status 2>/dev/null); "
            + "[ -n \"$r\" ] && echo \"${r#VmRSS:} ${p#/proc/} $(cat $p/comm 2>/dev/null)\"; done "
            + "| busybox sort -n -r\n"},
        {"log", "Android-Log (letzte 400 Zeilen)",
            "chroot /android /system/bin/logcat -d -v time 2>&1 | busybox tail -n 400\n"},
        {"cpu", "Prozessor", "cat /proc/cpuinfo\necho\ngrep -E '^cpu' /proc/stat\n"},
    };

    public static final boolean LOADED;
    private static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String err = null;
        try {
            System.loadLibrary("omkroot");
            int a = available();
            ok = a == 0;
            if (!ok) {
                err = "Kamerasystem nicht erreichbar (" + a + ")";
            }
        } catch (Throwable t) {
            err = t.getClass().getSimpleName();
        }
        LOADED = ok;
        LOAD_ERROR = err;
    }

    private RootShell() {
    }

    private static native int available();

    private static native int nativeExec(String cmd);

    /** Names of all queries, for the debug page and the MCP tool */
    public static String[] names() {
        String[] n = new String[QUERIES.length];
        for (int i = 0; i < n.length; i++) {
            n[i] = QUERIES[i][0];
        }
        return n;
    }

    public static String title(String name) {
        String[] q = find(name);
        return q != null ? q[1] : name;
    }

    private static String[] find(String name) {
        for (String[] q : QUERIES) {
            if (q[0].equals(name)) {
                return q;
            }
        }
        return null;
    }

    public static String status() {
        return LOADED ? "verfügbar" : "nicht verfügbar: " + LOAD_ERROR;
    }

    /**
     * Runs a query and returns its output, or a message starting with "Fehler" / "unbekannt".
     * Waits at most timeoutMs for the host.
     */
    public static synchronized String run(Context ctx, String name, long timeoutMs) {
        String[] q = find(name);
        if (q == null) {
            return "unbekannte Abfrage: " + name;
        }
        if (!LOADED) {
            return "Fehler: root nicht verfügbar (" + LOAD_ERROR + ")";
        }
        File dir = ctx.getFilesDir();
        File script = new File(dir, "root_" + name + ".sh");
        File out = new File(dir, "root_" + name + ".out");
        File tmp = new File(dir, "root_" + name + ".tmp");
        out.delete();
        tmp.delete();
        try {
            FileOutputStream fo = new FileOutputStream(script);
            fo.write(q[2].getBytes("UTF-8"));
            fo.close();
        } catch (IOException e) {
            return "Fehler beim Schreiben: " + e.getMessage();
        }
        // The host sees the app's folder below /android
        String b = "root_" + name;
        String cmd = "cd /android" + dir.getAbsolutePath() + " && sh " + b + ".sh > " + b + ".tmp 2>&1; chmod 644 "
                + b + ".tmp; mv " + b + ".tmp " + b + ".out";
        int err = nativeExec(cmd);
        if (err != 0) {
            return "Fehler: Befehl nicht angenommen (" + err + ")";
        }
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (out.exists()) {
                String s = read(out);
                out.delete();
                return s;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return "Fehler: unterbrochen";
            }
        }
        return "Fehler: keine Antwort vom Kamerasystem innerhalb " + (timeoutMs / 1000) + " s";
    }

    private static String read(File f) {
        try {
            InputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) Math.min(f.length(), 512 * 1024)];
            int n = 0;
            while (n < b.length) {
                int k = in.read(b, n, b.length - n);
                if (k < 0) {
                    break;
                }
                n += k;
            }
            in.close();
            return new String(b, 0, n, "UTF-8");
        } catch (IOException e) {
            return "Fehler beim Lesen: " + e.getMessage();
        }
    }
}
