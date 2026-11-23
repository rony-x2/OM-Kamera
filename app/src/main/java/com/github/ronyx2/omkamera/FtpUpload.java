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
 * Sends new pictures to an FTP server or an SMB share (NAS, Windows) over WLAN, one after the
 * other in the background. Plain FTP in passive mode or SMB 2/3 (Smb2), both on every model,
 * Android 2.3 included; the settings come from the remote control page, as the camera has no
 * keyboard. The class keeps its name and the "ftp." settings from the time it only spoke FTP.
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
    /** 0 = FTP, 1 = SMB (NAS or Windows share) */
    public int proto() { return prefs.getInt("ftp.proto", 0) == 1 ? 1 : 0; }
    public boolean smb() { return proto() == 1; }
    public int defaultPort() { return smb() ? 445 : 21; }
    public int port() { int p = prefs.getInt("ftp.port", 0); return p > 0 ? p : defaultPort(); }
    public String protoName() { return smb() ? "SMB" : "FTP"; }
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
    public void configure(Integer proto, String host, Integer port, String user, String pass, String dir, Integer what,
            Boolean results) {
        SharedPreferences.Editor e = prefs.edit();
        if (proto != null) e.putInt("ftp.proto", proto == 1 ? 1 : 0);
        if (host != null) e.putString("ftp.host", host.trim());
        if (port != null && port > 0 && port < 65536) e.putInt("ftp.port", port);
        else if (port != null && port == 0) e.remove("ftp.port"); // empty field: default port
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
        Transport f = transport();
        try {
            f.open(server(), port(), user(), prefs.getString("ftp.pass", ""), dir());
            for (File file : files) {
                if (!running) {
                    return;
                }
                current = file.getName();
                state = "sendet " + current;
                host.redraw();
                String sub = file.getParentFile().getName();
                long t0 = SystemClock.elapsedRealtime();
                f.store(file, sub, file.getName());
                long ms = Math.max(1, SystemClock.elapsedRealtime() - t0);
                sent++;
                waiting = Math.max(0, waiting - 1);
                remember(file);
                Log.info(String.format(Locale.US, "%s %s/%s %d KB in %.1f s (%.0f KB/s)", protoName().toLowerCase(Locale.US), sub, file.getName(),
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

    /** The target folder as it is shown: /dir on FTP, \\server\share\dir on SMB */
    public String target() {
        if (!smb()) {
            return server() + (port() != 21 ? ":" + port() : "") + " " + dir();
        }
        String[] sp = Smb2.splitShare(dir());
        return "\\\\" + server() + (port() != 445 ? ":" + port() : "") + "\\" + sp[0] + (sp[1].length() > 0 ? "\\" + sp[1] : "");
    }

    /** Connects, logs in and creates the target folder: for "Verbindung prüfen" */
    public String test() {
        if (!configured()) {
            return "Übertragung ist noch nicht eingerichtet (Fernsteuerungsseite im Browser)";
        }
        Transport f = transport();
        try {
            f.open(server(), port(), user(), prefs.getString("ftp.pass", ""), dir());
            f.makeDir();
            return "Verbindung in Ordnung: " + target() + f.info();
        } catch (Exception e) {
            return protoName() + ": " + e.getMessage();
        } finally {
            f.close();
        }
    }

    // ---- transports ----

    private interface Transport {
        void open(String host, int port, String user, String pass, String dir) throws IOException;

        /** Creates the target folder */
        void makeDir() throws IOException;

        /** Writes the file into the sub folder of the target folder */
        void store(File file, String sub, String name) throws IOException;

        String info();

        void close();
    }

    private Transport transport() {
        return smb() ? new SmbTransport() : new FtpTransport();
    }

    private static final class FtpTransport implements Transport {
        private final Ftp ftp = new Ftp();
        private String dir;

        public void open(String host, int port, String user, String pass, String dir) throws IOException {
            this.dir = dir;
            ftp.open(host, port, user, pass);
        }

        public void makeDir() throws IOException {
            ftp.cwdMake(dir);
        }

        public void store(File file, String sub, String name) throws IOException {
            ftp.store(file, join(dir, sub), name);
        }

        public String info() {
            return "";
        }

        public void close() {
            ftp.close();
        }
    }

    private static final class SmbTransport implements Transport {
        private final Smb2 smb = new Smb2();
        private String base;

        public void open(String host, int port, String user, String pass, String dir) throws IOException {
            String[] sp = Smb2.splitShare(dir);
            base = sp[1];
            smb.open(host, port, sp[0], user, pass);
        }

        public void makeDir() throws IOException {
            smb.makeDirs(base);
        }

        public void store(File file, String sub, String name) throws IOException {
            smb.store(file, base.length() > 0 ? base + "\\" + sub : sub, name);
        }

        public String info() {
            return " (" + smb.dialectName() + (smb.encrypted() ? ", verschlüsselt" : smb.signing() ? ", signiert" : "") + ")";
        }

        public void close() {
            smb.close();
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
