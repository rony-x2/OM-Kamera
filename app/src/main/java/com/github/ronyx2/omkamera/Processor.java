package com.github.ronyx2.omkamera;

import android.os.Environment;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

/**
 * Background jobs on ARW files (stage 3c): conversion to DNG, TIFF and PNG, and combinations
 * of several files (mean, sum, maximum, focus stack) as DNG. One job at a time on a worker
 * thread with low priority; results go to OMKAMERA on the card.
 */
public class Processor {

    public interface Host {
        /** Lens data of the camera right now (for files without lens data), or null */
        TiffOut.Lens lens();

        String version();

        void message(String text, boolean warn);

        void keepAwake();

        void redraw();
    }

    public enum Kind {
        DNG("DNG", ".DNG"), TIFF("TIFF 16 Bit", ".TIF"), PNG("PNG 16 Bit", ".PNG"),
        MEAN("Mittelwert", ".DNG"), SUM("Summe", ".DNG"), MAX("Maximum", ".DNG"), FOCUS("Fokus-Stacking", ".DNG");
        public final String label, ext;

        Kind(String label, String ext) {
            this.label = label;
            this.ext = ext;
        }

        boolean combines() {
            return ordinal() >= MEAN.ordinal();
        }
    }

    private static final class Job {
        final Kind kind;
        final List<File> files;

        Job(Kind kind, List<File> files) {
            this.kind = kind;
            this.files = files;
        }
    }

    private final Host host;
    private final LinkedList<Job> queue = new LinkedList<Job>();
    private Thread worker;
    private volatile boolean cancel;
    private volatile String current;
    private volatile int percent;
    private volatile String last = "noch nichts verarbeitet";
    /** Newest ARW seen, for the automatic conversion */
    private long lastSeen;

    public Processor(Host host) {
        this.host = host;
    }

    public static File outDir() {
        return new File(Environment.getExternalStorageDirectory(), "OMKAMERA");
    }

    // ---- finding ARW files ----

    /** The newest DCIM folder's ARW files, oldest first */
    public static List<File> arwFiles() {
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
        List<File> out = new ArrayList<File>();
        File[] subs = dcim.listFiles();
        if (subs == null) {
            return out;
        }
        Arrays.sort(subs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        for (File d : subs) {
            File[] fs = d.isDirectory() ? d.listFiles() : null;
            if (fs == null) {
                continue;
            }
            for (File f : fs) {
                if (f.getName().toUpperCase(java.util.Locale.US).endsWith(".ARW")) {
                    out.add(f);
                }
            }
        }
        Collections2.sortByTime(out);
        return out;
    }

    /** The newest n ARW files, oldest first */
    public static List<File> newest(int n) {
        List<File> all = arwFiles();
        return new ArrayList<File>(all.subList(Math.max(0, all.size() - n), all.size()));
    }

    public static File find(String name) {
        if (name == null || name.length() == 0) {
            return null;
        }
        File f = new File(name);
        if (f.isFile()) {
            return f;
        }
        for (File a : arwFiles()) {
            if (a.getName().equalsIgnoreCase(name) || a.getName().equalsIgnoreCase(name + ".ARW")) {
                return a;
            }
        }
        return null;
    }

    // ---- queue ----

    public synchronized String add(Kind kind, List<File> files) {
        if (files == null || files.isEmpty()) {
            return "keine ARW-Datei gefunden (Bildqualität RAW oder RAW+JPEG?)";
        }
        if (kind.combines() && files.size() < 2) {
            return "für " + kind.label + " sind mindestens 2 ARW-Dateien nötig";
        }
        queue.add(new Job(kind, files));
        Log.info("process queued " + kind + " " + files.size() + " file(s), first " + files.get(0).getName());
        if (worker == null) {
            cancel = false;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "processor");
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
        return kind.label + ": " + (files.size() == 1 ? files.get(0).getName() : files.size() + " Bilder")
                + " eingereiht" + (queue.size() > 1 || current != null ? " (Warteschlange " + queue.size() + ")" : "");
    }

    public synchronized void cancelAll() {
        queue.clear();
        cancel = true;
    }

    public boolean busy() {
        return current != null;
    }

    /** "DNG DSC05935 37 %" while working, otherwise null */
    public String progress() {
        String c = current;
        return c == null ? null : c + " " + percent + " %";
    }

    public String status() {
        String p = progress();
        int q;
        synchronized (this) {
            q = queue.size();
        }
        return (p != null ? "läuft: " + p + (q > 0 ? ", danach " + q + " weitere" : "") + "\n" : "")
                + "zuletzt: " + last;
    }

    private void loop() {
        while (true) {
            Job j;
            synchronized (this) {
                j = queue.poll();
                if (j == null) {
                    worker = null;
                    current = null;
                    host.redraw();
                    return;
                }
                cancel = false;
            }
            run(j);
        }
    }

    private void run(Job j) {
        File first = j.files.get(0), lastFile = j.files.get(j.files.size() - 1);
        String base = stem(lastFile.getName());
        String name = j.kind.combines() ? prefix(j.kind) + digits(base) + j.kind.ext : base + j.kind.ext;
        File out = new File(outDir(), name);
        current = j.kind.label + " " + (j.kind.combines() ? j.files.size() + " Bilder" : base);
        percent = 0;
        host.redraw();
        long t0 = SystemClock.uptimeMillis();
        RawTools.Rows src = null;
        try {
            src = j.kind.combines()
                    ? RawTools.combine(j.files, RawTools.Mode.valueOf(j.kind.name()))
                    : RawTools.single(new RawFile(first));
            Log.info("process " + j.kind + " " + src.meta().describe() + " -> " + out.getPath());
            TiffOut.Lens lens = host.lens();
            String sw = "OM Kamera " + host.version();
            RawTools.Progress p = new RawTools.Progress() {
                private long lastAwake;

                @Override
                public boolean step(int done, int total) {
                    int pc = done * 100 / total;
                    if (pc != percent) {
                        percent = pc;
                        host.redraw();
                    }
                    long now = SystemClock.uptimeMillis();
                    if (now - lastAwake > 5000) {
                        lastAwake = now;
                        host.keepAwake();
                    }
                    return !cancel;
                }
            };
            switch (j.kind) {
                case TIFF:
                    RawTools.writeTiff(out, src, lens, sw, p);
                    break;
                case PNG:
                    RawTools.writePng(out, src, lens, sw, p);
                    break;
                default:
                    RawTools.writeDng(out, src, lens, sw, j.kind.combines() ? null : first.getName(), p);
                    break;
            }
            long s = (SystemClock.uptimeMillis() - t0) / 1000;
            last = "OMKAMERA/" + name + " (" + (out.length() / (1024 * 1024)) + " MB, " + s + " s)";
            Log.info("process done " + last);
            host.message(j.kind.label + " fertig: " + name, false);
        } catch (Throwable e) {
            Log.error("process " + j.kind, e);
            last = j.kind.label + " fehlgeschlagen: " + e.getMessage();
            host.message(last, true);
        } finally {
            if (src != null) {
                src.close();
            }
            current = null;
            host.redraw();
        }
    }

    private static String stem(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** Last five digits of a file name such as DSC05935, for 8.3 names of combinations */
    private static String digits(String base) {
        String d = base.replaceAll("[^0-9]", "");
        if (d.length() > 5) {
            d = d.substring(d.length() - 5);
        }
        while (d.length() < 5) {
            d = "0" + d;
        }
        return d;
    }

    private static String prefix(Kind k) {
        switch (k) {
            case MEAN: return "MIT";
            case SUM: return "SUM";
            case MAX: return "MAX";
            default: return "FST";
        }
    }

    // ---- automatic conversion after each RAW shot ----

    /** Called when the camera finished writing; queues the new ARW for the selected format */
    public void onCardWritten(Kind auto) {
        if (auto == null) {
            return;
        }
        List<File> all = arwFiles();
        if (all.isEmpty()) {
            return;
        }
        File n = all.get(all.size() - 1);
        if (lastSeen == 0) {
            // First call: only files from now on
            lastSeen = n.lastModified();
            return;
        }
        List<File> fresh = new ArrayList<File>();
        for (File f : all) {
            if (f.lastModified() > lastSeen) {
                fresh.add(f);
            }
        }
        if (!fresh.isEmpty()) {
            lastSeen = fresh.get(fresh.size() - 1).lastModified();
            for (File f : fresh) {
                add(auto, java.util.Collections.singletonList(f));
            }
        }
    }

    public void markSeen() {
        List<File> all = arwFiles();
        lastSeen = all.isEmpty() ? 1 : all.get(all.size() - 1).lastModified();
    }

    /** Sorting by modification time, then name (several shots in one second) */
    static final class Collections2 {
        static void sortByTime(List<File> l) {
            java.util.Collections.sort(l, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    long d = a.lastModified() - b.lastModified();
                    return d != 0 ? (d < 0 ? -1 : 1) : a.getName().compareTo(b.getName());
                }
            });
        }
    }
}
