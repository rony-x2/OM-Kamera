package com.github.ronyx2.omkamera;

import android.content.SharedPreferences;
import android.os.Environment;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Sends new pictures to an FTP server over WLAN, one after the other in the background.
 * Plain FTP in passive mode (works on every model, Android 2.3 included); the settings come
 * from the remote control page, as the camera has no keyboard.
 *
 * Which files: new pictures in DCIM (all, only JPEG or only RAW) and, if wished, the results
 * of the processing in OMKAMERA (DNG, TIFF, films …). The last sent file is remembered, so
 * nothing is sent twice and pictures from before switching on are left out.
 */
public final class FtpUpload {
    public interface Host {
        boolean wifiConnected();

        void message(String text, boolean warn);

        void redraw();
    }

    public static final String[] WHAT = {"alle Bilder", "nur JPEG", "nur RAW (ARW)"};

    private final SharedPreferences prefs;
    private final Host host;
    private volatile boolean running;
    private Thread worker;
    private volatile String state = "aus";
    private volatile int sent;
    private volatile int waiting;
    private volatile String current;

    public FtpUpload(SharedPreferences prefs, Host host) {
        this.prefs = prefs;
        this.host = host;
    }

    // ---- settings ----

    public boolean on() { return prefs.getBoolean("ftp.on", false); }
    public String server() { return prefs.getString("ftp.host", ""); }
    public int port() { return prefs.getInt("ftp.port", 21); }
    public String user() { return prefs.getString("ftp.user", ""); }
    public boolean hasPassword() { return prefs.getString("ftp.pass", "").length() > 0; }
    public String dir() { return prefs.getString("ftp.dir", "/"); }
    public int what() { return Math.max(0, Math.min(WHAT.length - 1, prefs.getInt("ftp.what", 0))); }
    public boolean results() { return prefs.getBoolean("ftp.results", false); }
    public String state() { return state; }
    public int sent() { return sent; }
    public int waiting() { return waiting; }

    public boolean configured() {
        return server().length() > 0;
    }

    /**
     * Stores the settings from the remote page; null leaves a value as it is (the password
     * is never sent back to the page, an empty field keeps it).
     */
    public void configure(String host, Integer port, String user, String pass, String dir, Integer what, Boolean results) {
        SharedPreferences.Editor e = prefs.edit();
        if (host != null) e.putString("ftp.host", host.trim());
        if (port != null && port > 0 && port < 65536) e.putInt("ftp.port", port);
        if (user != null) e.putString("ftp.user", user.trim());
        if (pass != null && pass.length() > 0) e.putString("ftp.pass", pass);
        if (dir != null) e.putString("ftp.dir", dir.trim().length() == 0 ? "/" : dir.trim());
        if (what != null) e.putInt("ftp.what", what);
        if (results != null) e.putBoolean("ftp.results", results);
        e.apply();
    }

    public void setOn(boolean on) {
        if (on && !prefs.getBoolean("ftp.on", false)) {
            // Only pictures taken from now on
            prefs.edit().putLong("ftp.since", System.currentTimeMillis()).apply();
        }
        prefs.edit().putBoolean("ftp.on", on).apply();
        if (on) {
            start();
        } else {
            stop();
            state = "aus";
        }
    }

    public void setWhat(int w) {
        prefs.edit().putInt("ftp.what", (w + WHAT.length) % WHAT.length).apply();
    }

    public void setResults(boolean r) {
        prefs.edit().putBoolean("ftp.results", r).apply();
    }

    /** Short text for the menu and the status */
    public String summary() {
        if (!configured()) {
            return "nicht eingerichtet";
        }
        if (!on()) {
            return "aus";
        }
        return state + (sent > 0 ? " · " + sent + " gesendet" : "") + (waiting > 0 ? " · " + waiting + " offen" : "");
    }

    // ---- running ----

    public synchronized void start() {
        if (!on() || !configured() || (worker != null && worker.isAlive())) {
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "ftp");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void loop() {
        state = "wartet auf neue Bilder";
        while (running) {
            try {
                if (!host.wifiConnected()) {
                    state = "wartet auf WLAN";
                    sleep(5000);
                    continue;
                }
                List<File> todo = pending();
                waiting = todo.size();
                if (todo.isEmpty()) {
                    state = "wartet auf neue Bilder";
                    sleep(4000);
                    continue;
                }
                sendAll(todo);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                state = "Fehler: " + e.getMessage();
                Log.info("ftp " + state);
                host.redraw();
                try {
                    sleep(30000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    /** Files that are new since the last sent one and finished (unchanged for 3 s) */
    private List<File> pending() {
        long since = prefs.getLong("ftp.since", 0);
        long last = Math.max(since, prefs.getLong("ftp.last", 0));
        // The card stores times in 2-second steps: files with the same time as the last sent
        // one are told apart by name
        java.util.Set<String> sameTime = new java.util.HashSet<String>(
                Arrays.asList(prefs.getString("ftp.lastnames", "").split(",")));
        List<File> out = new ArrayList<File>();
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
        File[] dirs = dcim.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory() || d.lastModified() < last - 2000) {
                    continue;
                }
                File[] fs = d.listFiles();
                if (fs == null) {
                    continue;
                }
                for (File f : fs) {
                    if (wanted(f.getName()) && isNew(f, last, sameTime)) {
                        out.add(f);
                    }
                }
            }
        }
        if (results()) {
            File[] fs = new File(Environment.getExternalStorageDirectory(), "OMKAMERA").listFiles();
            if (fs != null) {
                for (File f : fs) {
                    String n = f.getName().toUpperCase(Locale.US);
                    if (f.isFile() && isNew(f, last, sameTime) && !n.endsWith(".LOG") && !n.endsWith(".TXT")
                            && !n.endsWith(".JSON") && !n.startsWith(".")) {
                        out.add(f);
                    }
                }
            }
        }
        // Oldest first; files still being written wait for the next round
        File[] arr = out.toArray(new File[out.size()]);
        Arrays.sort(arr, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.lastModified() < b.lastModified() ? -1 : a.lastModified() > b.lastModified() ? 1 : 0;
            }
        });
        List<File> ready = new ArrayList<File>();
        long now = System.currentTimeMillis();
        for (File f : arr) {
            if (now - f.lastModified() < 3000) {
                break;
            }
            ready.add(f);
        }
        return ready;
    }

    private static boolean isNew(File f, long last, java.util.Set<String> sameTime) {
        long m = f.lastModified();
        return m > last || (m == last && !sameTime.contains(f.getParentFile().getName() + "/" + f.getName()));
    }

    /** Remembers the sent file, so it is not sent again */
    private void remember(File file) {
        long last = prefs.getLong("ftp.last", 0);
        long m = file.lastModified();
        String key = file.getParentFile().getName() + "/" + file.getName();
        if (m > last) {
            prefs.edit().putLong("ftp.last", m).putString("ftp.lastnames", key).apply();
        } else if (m == last) {
            prefs.edit().putString("ftp.lastnames", prefs.getString("ftp.lastnames", "") + "," + key).apply();
        }
    }

    private boolean wanted(String name) {
        String n = name.toUpperCase(Locale.US);
        boolean jpg = n.endsWith(".JPG");
        boolean raw = n.endsWith(".ARW");
        switch (what()) {
            case 1: return jpg;
            case 2: return raw;
            default: return jpg || raw || n.endsWith(".MP4");
        }
    }

    private void sendAll(List<File> files) throws IOException, InterruptedException {
        Ftp f = new Ftp();
        try {
            f.open(server(), port(), user(), prefs.getString("ftp.pass", ""));
            for (File file : files) {
                if (!running) {
                    return;
                }
                current = file.getName();
                state = "sendet " + current;
                host.redraw();
                String sub = file.getParentFile().getName();
                long t0 = SystemClock.elapsedRealtime();
                f.store(file, join(dir(), sub), file.getName());
                long ms = Math.max(1, SystemClock.elapsedRealtime() - t0);
                sent++;
                waiting = Math.max(0, waiting - 1);
                remember(file);
                Log.info(String.format(Locale.US, "ftp %s/%s %d KB in %.1f s (%.0f KB/s)", sub, file.getName(),
                        file.length() / 1024, ms / 1000.0, file.length() / 1024.0 / (ms / 1000.0)));
            }
            current = null;
            state = "wartet auf neue Bilder";
        } finally {
            f.close();
        }
    }

    private static String join(String dir, String sub) {
        String d = dir.endsWith("/") ? dir : dir + "/";
        return d + sub;
    }

    /** Connects, logs in and changes to the target folder: for "Verbindung prüfen" */
    public String test() {
        if (!configured()) {
            return "FTP ist noch nicht eingerichtet (Fernsteuerungsseite im Browser)";
        }
        Ftp f = new Ftp();
        try {
            f.open(server(), port(), user(), prefs.getString("ftp.pass", ""));
            f.cwdMake(dir());
            return "FTP-Verbindung in Ordnung: " + server() + dir();
        } catch (Exception e) {
            return "FTP: " + e.getMessage();
        } finally {
            f.close();
        }
    }

    // ---- minimal FTP client (RFC 959, passive mode, binary) ----

    private static final class Ftp {
        private Socket ctrl;
        private BufferedReader in;
        private OutputStream out;
        private String made = "";

        void open(String host, int port, String user, String pass) throws IOException {
            ctrl = new Socket();
            ctrl.connect(new InetSocketAddress(host, port), 8000);
            ctrl.setSoTimeout(20000);
            in = new BufferedReader(new InputStreamReader(ctrl.getInputStream(), "UTF-8"));
            out = ctrl.getOutputStream();
            expect(reply(), 2, "Server antwortet nicht");
            String r = cmd("USER " + (user.length() > 0 ? user : "anonymous"));
            if (r.startsWith("3")) {
                r = cmd("PASS " + pass);
            }
            if (!r.startsWith("2")) {
                throw new IOException("Anmeldung abgelehnt (" + r.trim() + ")");
            }
            expect(cmd("TYPE I"), 2, "Binärmodus abgelehnt");
        }

        /** Changes to the folder, creating missing parts */
        void cwdMake(String path) throws IOException {
            if (path.equals(made)) {
                return;
            }
            String r = cmd("CWD " + path);
            if (!r.startsWith("2")) {
                String cur = path.startsWith("/") ? "/" : "";
                for (String part : path.split("/")) {
                    if (part.length() == 0) {
                        continue;
                    }
                    cur = cur.length() == 0 || cur.endsWith("/") ? cur + part : cur + "/" + part;
                    if (!cmd("CWD " + cur).startsWith("2")) {
                        cmd("MKD " + cur);
                        expect(cmd("CWD " + cur), 2, "Ordner " + cur + " nicht anlegbar");
                    }
                }
            }
            made = path;
        }

        void store(File file, String dir, String name) throws IOException {
            cwdMake(dir);
            Socket data = passive();
            try {
                String r = cmd("STOR " + name);
                if (!r.startsWith("1") && !r.startsWith("2")) {
                    throw new IOException("Schreiben abgelehnt (" + r.trim() + ")");
                }
                InputStream fin = new FileInputStream(file);
                OutputStream dout = data.getOutputStream();
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fin.read(buf)) > 0) {
                        dout.write(buf, 0, n);
                    }
                    dout.flush();
                } finally {
                    fin.close();
                }
            } finally {
                data.close();
            }
            expect(reply(), 2, "Übertragung von " + name + " nicht bestätigt");
        }

        private Socket passive() throws IOException {
            String r = cmd("PASV");
            if (!r.startsWith("227")) {
                throw new IOException("Passiver Modus abgelehnt (" + r.trim() + ")");
            }
            int a = r.indexOf('('), b = r.indexOf(')', a);
            String[] p = r.substring(a + 1, b).split(",");
            int port = Integer.parseInt(p[4].trim()) * 256 + Integer.parseInt(p[5].trim());
            // Use the control connection's address: some servers report an internal one
            Socket s = new Socket();
            s.connect(new InetSocketAddress(ctrl.getInetAddress(), port), 8000);
            s.setSoTimeout(30000);
            return s;
        }

        private String cmd(String c) throws IOException {
            out.write((c + "\r\n").getBytes("UTF-8"));
            out.flush();
            return reply();
        }

        /** Reads a reply, including multi-line ones ("123-" … "123 ") */
        private String reply() throws IOException {
            String l = in.readLine();
            if (l == null) {
                throw new IOException("Verbindung beendet");
            }
            if (l.length() > 3 && l.charAt(3) == '-') {
                String code = l.substring(0, 3);
                String n;
                while ((n = in.readLine()) != null) {
                    if (n.startsWith(code + " ")) {
                        return n;
                    }
                }
            }
            return l;
        }

        private static void expect(String reply, int cls, String what) throws IOException {
            if (reply == null || reply.length() == 0 || reply.charAt(0) - '0' != cls) {
                throw new IOException(what + (reply != null ? " (" + reply.trim() + ")" : ""));
            }
        }

        void close() {
            try {
                if (out != null) {
                    out.write("QUIT\r\n".getBytes("UTF-8"));
                    out.flush();
                }
            } catch (IOException ignored) {
            }
            try {
                if (ctrl != null) {
                    ctrl.close();
                }
            } catch (IOException ignored) {
            }
        }
    }
}
