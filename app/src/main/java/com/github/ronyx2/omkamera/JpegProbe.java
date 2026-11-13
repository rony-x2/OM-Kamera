package com.github.ronyx2.omkamera;

import android.os.Environment;

import com.sony.scalar.graphics.JpegExporter;
import com.sony.scalar.graphics.OptimizedImage;
import com.sony.scalar.graphics.OptimizedImageFactory;

import org.json.JSONArray;

import java.io.File;

/**
 * Experiment: re-encodes a JPEG from the card with Sony's hardware JpegExporter at several
 * quality values and reports the file sizes. Shows whether the encoder accepts more than
 * its three documented levels (STANDARD 1, FINE 2, EXTRAFINE 3).
 */
final class JpegProbe {
    private JpegProbe() {
    }

    static String run(String source, JSONArray levels, boolean delete) {
        File in = source != null && source.length() > 0 ? new File(source) : newestJpeg();
        if (in == null || !in.isFile()) {
            return "Kein JPEG gefunden" + (source != null ? ": " + source : " im DCIM-Ordner (Qualität auf JPEG stellen und ein Bild aufnehmen)");
        }
        StringBuilder out = new StringBuilder();
        waitStable(in);
        out.append("Quelle: ").append(in.getPath()).append(" (").append(in.length() / 1024).append(" KB)\n");
        // The newest files of the folder with their final sizes, to compare shots
        File[] siblings = in.getParentFile() != null ? in.getParentFile().listFiles() : null;
        if (siblings != null) {
            java.util.Arrays.sort(siblings, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            out.append("Letzte Dateien:");
            for (int i = Math.max(0, siblings.length - 8); i < siblings.length; i++) {
                out.append(' ').append(siblings[i].getName()).append('=').append(siblings[i].length() / 1024).append("KB");
            }
            out.append('\n');
        }
        File dir = new File(Environment.getExternalStorageDirectory(), "OMKTEST");
        dir.mkdirs();
        OptimizedImage image = null;
        JpegExporter exporter = null;
        try {
            long t0 = System.currentTimeMillis();
            // The decoder runs outside Android's chroot, so the card may have another path there
            String[] paths = {in.getPath(), "/android" + in.getPath()};
            for (String path : paths) {
                OptimizedImageFactory.Options o = new OptimizedImageFactory.Options();
                o.imageType = OptimizedImageFactory.Options.MAIN;
                image = OptimizedImageFactory.decodeImage(path, o);
                out.append("decodeImage ").append(path).append(": ").append(image != null ? "ok" : "nichts").append('\n');
                if (image != null) {
                    break;
                }
            }
            if (image == null) {
                return out.toString();
            }
            out.append("dekodiert ").append(image.getWidth()).append('x').append(image.getHeight())
                    .append(" in ").append(System.currentTimeMillis() - t0).append(" ms\n");
            exporter = new JpegExporter();
            int count = levels != null && levels.length() > 0 ? levels.length() : 3;
            for (int i = 0; i < count; i++) {
                int q = levels != null && levels.length() > 0 ? levels.optInt(i) : i + 1;
                File target = new File(dir, "Q" + q + ".JPG");
                target.delete();
                JpegExporter.Options o = new JpegExporter.Options();
                o.quality = q;
                o.rotateDegree = JpegExporter.Options.ROTATE_DEGREE_0;
                long t = System.currentTimeMillis();
                try {
                    exporter.encode(image, target.getPath(), o);
                    out.append("Stufe ").append(q).append(": ")
                            .append(target.isFile() ? target.length() / 1024 + " KB" : "keine Datei")
                            .append(", ").append(System.currentTimeMillis() - t).append(" ms\n");
                } catch (Throwable e) {
                    out.append("Stufe ").append(q).append(": Fehler ").append(e).append('\n');
                }
                if (delete) {
                    target.delete();
                }
            }
        } catch (Throwable e) {
            Log.error("jpeg probe", e);
            out.append("Fehler: ").append(e).append('\n');
        } finally {
            if (exporter != null) {
                try {
                    exporter.release();
                } catch (Throwable ignored) {
                }
            }
            if (image != null) {
                try {
                    image.release();
                } catch (Throwable ignored) {
                }
            }
            if (delete) {
                dir.delete();
            }
        }
        Log.info("jpeg probe\n" + out);
        return out.toString();
    }

    /** Waits until the file stops growing (the camera writes in the background), at most 15 s */
    private static void waitStable(File f) {
        long last = -1;
        for (int i = 0; i < 30; i++) {
            long now = f.length();
            if (now == last && now > 0) {
                return;
            }
            last = now;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static File newestJpeg() {
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
        File best = null;
        File[] folders = dcim.listFiles();
        if (folders == null) {
            return null;
        }
        for (File f : folders) {
            File[] files = f.isDirectory() ? f.listFiles() : null;
            if (files == null) {
                continue;
            }
            for (File x : files) {
                if (x.getName().toUpperCase().endsWith(".JPG") && (best == null || x.lastModified() > best.lastModified())) {
                    best = x;
                }
            }
        }
        return best;
    }
}
