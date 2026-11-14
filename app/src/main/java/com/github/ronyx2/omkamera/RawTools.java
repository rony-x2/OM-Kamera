package com.github.ronyx2.omkamera;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Works on ARW sensor data without the camera's image processor, row by row so that a 24 MP
 * image fits into the app's 24 MB heap:
 * - DNG: the sensor data unchanged, with black/white level, white balance, colour matrix and
 *   the EXIF data incl. lens; for single files and for combinations of several files
 * - TIFF with 16 bits and PNG with 16 or 8 bits per channel: a simple, neutral development (bilinear demosaic,
 *   white balance as shot, camera matrix to sRGB, sRGB tone curve)
 * - Combinations of several ARW files: mean (noise reduction), sum, maximum (star trails)
 *   and focus stacking (per 2x2 block the sharpest file)
 * Pure Java, testable on a PC.
 */
public final class RawTools {

    private RawTools() {
    }

    /** Called per row; returns false to cancel */
    public interface Progress {
        boolean step(int done, int total);
    }

    /** CFA rows of the full sensor width (RawFile.width) */
    public interface Rows {
        RawFile meta();

        void row(int y, int[] out) throws IOException;

        void close();
    }

    public static Rows single(final RawFile f) {
        return new Rows() {
            @Override
            public RawFile meta() {
                return f;
            }

            @Override
            public void row(int y, int[] out) throws IOException {
                f.readRow(y, out);
            }

            @Override
            public void close() {
                f.close();
            }
        };
    }

    // ---- colour data ----

    /** XYZ (D65) to camera matrices ×10000, from the public Adobe DNG data */
    static int[] colorMatrix(String model) {
        String m = model == null ? "" : model.toUpperCase(java.util.Locale.US);
        if (m.contains("ILCE-7RM2")) {
            return new int[]{6629, -1900, -483, -4618, 12349, 2550, -622, 1381, 6514};
        }
        if (m.contains("ILCE-7M2")) {
            return new int[]{5271, -712, -347, -6153, 13653, 2763, -1601, 2366, 7242};
        }
        // ILCE-6000 and fallback
        return new int[]{5991, -1456, -455, -4764, 12135, 2980, -707, 1425, 6701};
    }

    // ---- DNG ----

    public static void writeDng(File out, Rows src, TiffOut.Lens lens, String software, String originalName,
                                Progress p) throws IOException {
        RawFile m = src.meta();
        int w = m.width, h = m.height;
        TiffOut.Dir root = new TiffOut.Dir();
        root.longs(0x00fe, 0);
        root.longs(0x0100, w);
        root.longs(0x0101, h);
        root.shorts(0x0102, 16);
        root.shorts(0x0103, 1);
        root.shorts(0x0106, 32803);
        root.shorts(0x0115, 1);
        root.longs(0x0116, h);
        root.longs(0x0117, (long) w * h * 2);
        root.shorts(0x011c, 1);
        root.shorts(0x828d, 2, 2);
        root.bytes(0x828e, m.cfa[0], m.cfa[1], m.cfa[2], m.cfa[3]);
        root.bytes(50706, 1, 4, 0, 0);
        root.bytes(50707, 1, 1, 0, 0);
        root.ascii(50708, (m.make.length() > 0 ? capitalize(m.make) + " " : "") + m.model);
        root.bytes(50710, 0, 1, 2);
        root.shorts(50711, 1);
        root.shorts(50713, 2, 2);
        root.longs(50714, m.black[0], m.black[1], m.black[2], m.black[3]);
        root.longs(50717, m.white);
        root.longs(50719, m.cropX, m.cropY);
        root.longs(50720, m.cropW, m.cropH);
        int[] cm = colorMatrix(m.model);
        long[] nd = new long[18];
        for (int i = 0; i < 9; i++) {
            nd[i * 2] = cm[i] & 0xFFFFFFFFL;
            nd[i * 2 + 1] = 10000;
        }
        root.rationals(50721, true, nd);
        root.shorts(50778, 21);
        root.rationals(50728, false, Math.round(1e6 / m.wb[0]), 1000000, 1000000, 1000000,
                Math.round(1e6 / m.wb[2]), 1000000);
        if (originalName != null) {
            root.ascii(50827, originalName);
        }
        TiffOut.Dir exif = TiffOut.metadata(m, root, lens, software);
        TiffOut.lensInfo(root, exif);
        byte[] head = TiffOut.layout(root, 0x0111);

        OutputStream o = open(out);
        boolean ok = false;
        try {
            o.write(head);
            int[] row = new int[m.rawWidth];
            byte[] b = new byte[w * 2];
            boolean nat = nativeOn();
            long tRead = 0, tWrite = 0;
            for (int y = 0; y < h; y++) {
                long t0 = System.nanoTime();
                src.row(y, row);
                long t1 = System.nanoTime();
                if (nat) {
                    RawCore.pack16(row, w, b);
                } else {
                    for (int x = 0; x < w; x++) {
                        int v = row[x];
                        b[x * 2] = (byte) v;
                        b[x * 2 + 1] = (byte) (v >> 8);
                    }
                }
                o.write(b);
                tRead += t1 - t0;
                tWrite += System.nanoTime() - t1;
                if (p != null && !p.step(y + 1, h)) {
                    throw new IOException("abgebrochen");
                }
            }
            long t2 = System.nanoTime();
            o.close();
            o = null;
            timing = "lesen " + tRead / 1000000 + " ms, schreiben " + (tWrite + System.nanoTime() - t2) / 1000000 + " ms";
            ok = true;
        } finally {
            if (o != null) {
                o.close();
            }
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        }
    }

    /** Time split of the last DNG: reading/decoding versus writing to the card */
    public static volatile String timing = "";

    private static String capitalize(String s) {
        String l = s.toLowerCase(java.util.Locale.US);
        return l.length() == 0 ? l : Character.toUpperCase(l.charAt(0)) + l.substring(1);
    }

    /** The card's file system refuses to truncate (EBUSY): delete first, then create */
    static OutputStream open(File out) throws IOException {
        if (out.exists() && !out.delete()) {
            throw new IOException("kann " + out.getName() + " nicht ersetzen");
        }
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new BufferedOutputStream(new FileOutputStream(out), 256 * 1024);
    }

    // ---- development to 16 bit RGB ----

    /** Receives developed bytes: TIFF rows (LE) or PNG rows with filter byte */
    interface ByteSink {
        void write(byte[] b, int len) throws IOException;
    }

    static boolean nativeOn() {
        try {
            return RawCore.on();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Bilinear demosaic of the visible area, white balance as shot, camera matrix to sRGB, sRGB
     * curve. format 0: TIFF rows, 16 bit little endian; 1: PNG rows, 16 bit big endian with
     * filter Sub. Uses the native library (all cores) when it is loaded.
     */
    static void develop(Rows src, int format, ByteSink sink, Progress p) throws IOException {
        RawFile m = src.meta();
        int w = m.cropW, h = m.cropH, x0 = m.cropX, y0 = m.cropY;
        int fullW = m.width;
        int[] scale = new int[3];
        for (int c = 0; c < 3; c++) {
            scale[c] = (int) Math.round(m.wb[c] * 65535.0 * 4096 / (m.white - m.black[0]));
        }
        float[] rc = rgbFromCam(colorMatrix(m.model));
        int[] mat = new int[9];
        for (int i = 0; i < 9; i++) {
            mat[i] = Math.round(rc[i] * 4096);
        }
        int[] gamma = srgbLut();
        int stride = format == 2 ? 1 + w * 3 : format == 1 ? 1 + w * 6 : w * 6;

        if (nativeOn()) {
            final int B = 32;
            short[] lut = new short[65536];
            for (int i = 0; i < lut.length; i++) {
                lut[i] = (short) gamma[i];
            }
            int[] blk = {m.black[0], m.black[1], m.black[2], m.black[3]};
            int[] block = new int[(B + 2) * fullW];
            int[] raw = new int[m.rawWidth];
            byte[] out = new byte[B * stride];
            for (int y = 0; y < h; y += B) {
                int n = Math.min(B, h - y);
                for (int k = 0; k < n + 2; k++) {
                    int sy = Math.max(0, Math.min(m.height - 1, y0 + y - 1 + k));
                    src.row(sy, raw);
                    System.arraycopy(raw, 0, block, k * fullW, fullW);
                }
                int err = RawCore.develop(block, n, fullW, x0, w, y0 + y, m.cfa, blk, scale, mat, lut, format, out);
                if (err != 0) {
                    throw new IOException("Entwicklung nativ fehlgeschlagen (" + err + ")");
                }
                sink.write(out, n * stride);
                if (p != null && !p.step(y + n, h)) {
                    throw new IOException("abgebrochen");
                }
            }
            return;
        }

        int[] blk = m.black;
        int[][] ring = new int[3][fullW];
        int[] raw = new int[m.rawWidth];
        byte[] line = new byte[stride];
        load(src, y0 - 1, raw, ring[0], m, scale, blk);
        load(src, y0, raw, ring[1], m, scale, blk);
        for (int y = 0; y < h; y++) {
            int ry = y0 + y;
            int[] prev = ring[y % 3], cur = ring[(y + 1) % 3], next = ring[(y + 2) % 3];
            load(src, ry + 1, raw, next, m, scale, blk);
            int rowPhase = (ry & 1) * 2;
            int o = 0;
            if (format >= 1) {
                line[o++] = 1;
            }
            int pr = 0, pg = 0, pb = 0;
            for (int x = 0; x < w; x++) {
                int rx = x0 + x;
                int c = m.cfa[rowPhase + (rx & 1)];
                int r, g, b;
                int v = cur[rx];
                if (c == 1) {
                    int hc = m.cfa[rowPhase + ((rx + 1) & 1)];
                    int hv = (cur[rx - 1] + cur[rx + 1]) >> 1;
                    int vv = (prev[rx] + next[rx]) >> 1;
                    g = v;
                    if (hc == 0) {
                        r = hv;
                        b = vv;
                    } else {
                        r = vv;
                        b = hv;
                    }
                } else {
                    g = (cur[rx - 1] + cur[rx + 1] + prev[rx] + next[rx]) >> 2;
                    int d = (prev[rx - 1] + prev[rx + 1] + next[rx - 1] + next[rx + 1]) >> 2;
                    if (c == 0) {
                        r = v;
                        b = d;
                    } else {
                        r = d;
                        b = v;
                    }
                }
                int R = gamma[clamp((mat[0] * r + mat[1] * g + mat[2] * b) >> 12)];
                int G = gamma[clamp((mat[3] * r + mat[4] * g + mat[5] * b) >> 12)];
                int B = gamma[clamp((mat[6] * r + mat[7] * g + mat[8] * b) >> 12)];
                if (format == 2) {
                    line[o] = (byte) ((R >> 8) - (pr >> 8));
                    line[o + 1] = (byte) ((G >> 8) - (pg >> 8));
                    line[o + 2] = (byte) ((B >> 8) - (pb >> 8));
                    pr = R;
                    pg = G;
                    pb = B;
                    o += 3;
                    continue;
                }
                if (format == 1) {
                    // PNG filter Sub on bytes: each byte minus the same byte of the previous pixel
                    line[o] = (byte) ((R >> 8) - (pr >> 8));
                    line[o + 1] = (byte) ((R & 0xFF) - (pr & 0xFF));
                    line[o + 2] = (byte) ((G >> 8) - (pg >> 8));
                    line[o + 3] = (byte) ((G & 0xFF) - (pg & 0xFF));
                    line[o + 4] = (byte) ((B >> 8) - (pb >> 8));
                    line[o + 5] = (byte) ((B & 0xFF) - (pb & 0xFF));
                    pr = R;
                    pg = G;
                    pb = B;
                } else {
                    line[o] = (byte) R;
                    line[o + 1] = (byte) (R >> 8);
                    line[o + 2] = (byte) G;
                    line[o + 3] = (byte) (G >> 8);
                    line[o + 4] = (byte) B;
                    line[o + 5] = (byte) (B >> 8);
                }
                o += 6;
            }
            sink.write(line, stride);
            if (p != null && !p.step(y + 1, h)) {
                throw new IOException("abgebrochen");
            }
        }
    }

    private static void load(Rows src, int y, int[] raw, int[] out, RawFile m, int[] scale, int[] blk) throws IOException {
        y = Math.max(0, Math.min(m.height - 1, y));
        src.row(y, raw);
        int phase = (y & 1) * 2;
        for (int x = 0; x < m.width; x++) {
            int c = m.cfa[phase + (x & 1)];
            int v = raw[x] - blk[phase + (x & 1)];
            if (v < 0) {
                v = 0;
            }
            long s = ((long) v * scale[c]) >> 12;
            out[x] = s > 65535 ? 65535 : (int) s;
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 65535 ? 65535 : v;
    }

    private static int[] srgbLut() {
        int[] lut = new int[65536];
        for (int i = 0; i < lut.length; i++) {
            double x = i / 65535.0;
            double y = x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055;
            lut[i] = (int) Math.round(Math.max(0, Math.min(1, y)) * 65535);
        }
        return lut;
    }

    /** sRGB from white balanced camera RGB: inverse of (XYZ→camera × sRGB→XYZ), rows normalised */
    static float[] rgbFromCam(int[] xyzToCam) {
        double[][] xyzRgb = {
            {0.412453, 0.357580, 0.180423},
            {0.212671, 0.715160, 0.072169},
            {0.019334, 0.119193, 0.950227}};
        double[][] camRgb = new double[3][3];
        for (int i = 0; i < 3; i++) {
            double sum = 0;
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) {
                    s += xyzToCam[i * 3 + k] / 10000.0 * xyzRgb[k][j];
                }
                camRgb[i][j] = s;
                sum += s;
            }
            for (int j = 0; j < 3; j++) {
                camRgb[i][j] /= sum;
            }
        }
        double[][] inv = invert(camRgb);
        float[] out = new float[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i * 3 + j] = (float) inv[i][j];
            }
        }
        return out;
    }

    private static double[][] invert(double[][] a) {
        double det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
                - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
        double[][] r = new double[3][3];
        r[0][0] = (a[1][1] * a[2][2] - a[1][2] * a[2][1]) / det;
        r[0][1] = (a[0][2] * a[2][1] - a[0][1] * a[2][2]) / det;
        r[0][2] = (a[0][1] * a[1][2] - a[0][2] * a[1][1]) / det;
        r[1][0] = (a[1][2] * a[2][0] - a[1][0] * a[2][2]) / det;
        r[1][1] = (a[0][0] * a[2][2] - a[0][2] * a[2][0]) / det;
        r[1][2] = (a[0][2] * a[1][0] - a[0][0] * a[1][2]) / det;
        r[2][0] = (a[1][0] * a[2][1] - a[1][1] * a[2][0]) / det;
        r[2][1] = (a[0][1] * a[2][0] - a[0][0] * a[2][1]) / det;
        r[2][2] = (a[0][0] * a[1][1] - a[0][1] * a[1][0]) / det;
        return r;
    }

    // ---- TIFF 16 bit ----

    public static void writeTiff(File out, Rows src, TiffOut.Lens lens, String software, Progress p) throws IOException {
        RawFile m = src.meta();
        final int w = m.cropW, h = m.cropH;
        TiffOut.Dir root = new TiffOut.Dir();
        root.longs(0x00fe, 0);
        root.longs(0x0100, w);
        root.longs(0x0101, h);
        root.shorts(0x0102, 16, 16, 16);
        root.shorts(0x0103, 1);
        root.shorts(0x0106, 2);
        root.shorts(0x0115, 3);
        root.longs(0x0116, h);
        root.longs(0x0117, (long) w * h * 6);
        root.shorts(0x011c, 1);
        root.rationals(0x011a, false, 300, 1);
        root.rationals(0x011b, false, 300, 1);
        root.shorts(0x0128, 2);
        TiffOut.Dir exif = TiffOut.metadata(m, root, lens, software);
        // JPEG/compressed-data tags of the ARW do not belong into a TIFF
        exif.remove(0x9101).remove(0x9102).remove(0xa002).remove(0xa003);
        byte[] head = TiffOut.layout(root, 0x0111);
        final OutputStream o = open(out);
        boolean ok = false;
        try {
            o.write(head);
            develop(src, 0, new ByteSink() {
                @Override
                public void write(byte[] b, int len) throws IOException {
                    o.write(b, 0, len);
                }
            }, p);
            ok = true;
        } finally {
            o.close();
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        }
    }

    // ---- PNG 16 bit ----

    public static void writePng(File out, Rows src, TiffOut.Lens lens, String software, Progress p) throws IOException {
        writePng(out, src, 16, lens, software, p);
    }

    /** bits 16 or 8 per channel */
    public static void writePng(File out, Rows src, final int bits, TiffOut.Lens lens, String software, Progress p) throws IOException {
        RawFile m = src.meta();
        final int w = m.cropW, h = m.cropH;
        final OutputStream o = open(out);
        boolean ok = false;
        final Deflater def = new Deflater(1);
        try {
            o.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
            byte[] ihdr = new byte[13];
            be32(ihdr, 0, w);
            be32(ihdr, 4, h);
            ihdr[8] = (byte) (bits == 8 ? 8 : 16);
            ihdr[9] = 2;
            chunk(o, "IHDR", ihdr, 0, 13);
            chunk(o, "sRGB", new byte[]{0}, 0, 1);
            byte[] exif = TiffOut.exifBlock(m, lens, software);
            chunk(o, "eXIf", exif, 0, exif.length);
            if (nativeOn()) {
                // Parallel deflate on all cores: zlib header, raw deflate parts, Adler-32
                final int CH = 2 * 1024 * 1024;
                final byte[] in = new byte[CH];
                final byte[] zout = new byte[CH + CH / 8 + 8192];
                final long[] adler = {1};
                final int[] fill = {0};
                chunk(o, "IDAT", new byte[]{0x78, 0x01}, 0, 2);
                develop(src, bits == 8 ? 2 : 1, new ByteSink() {
                    @Override
                    public void write(byte[] b, int len) throws IOException {
                        int off = 0;
                        while (off < len) {
                            int n = Math.min(len - off, CH - fill[0]);
                            System.arraycopy(b, off, in, fill[0], n);
                            fill[0] += n;
                            off += n;
                            if (fill[0] == CH) {
                                int z = RawCore.deflate(in, CH, 1, false, zout, adler);
                                if (z < 0) {
                                    throw new IOException("Kompression fehlgeschlagen (" + z + ")");
                                }
                                chunk(o, "IDAT", zout, 0, z);
                                fill[0] = 0;
                            }
                        }
                    }
                }, p);
                int z = RawCore.deflate(in, fill[0], 1, true, zout, adler);
                if (z < 0) {
                    throw new IOException("Kompression fehlgeschlagen (" + z + ")");
                }
                byte[] tail = new byte[4];
                be32(tail, 0, adler[0]);
                byte[] last = new byte[z + 4];
                System.arraycopy(zout, 0, last, 0, z);
                System.arraycopy(tail, 0, last, z, 4);
                chunk(o, "IDAT", last, 0, last.length);
            } else {
                final byte[] buf = new byte[128 * 1024];
                develop(src, bits == 8 ? 2 : 1, new ByteSink() {
                    @Override
                    public void write(byte[] b, int len) throws IOException {
                        def.setInput(b, 0, len);
                        while (!def.needsInput()) {
                            int n = def.deflate(buf);
                            if (n > 0) {
                                chunk(o, "IDAT", buf, 0, n);
                            }
                        }
                    }
                }, p);
                def.finish();
                while (!def.finished()) {
                    int n = def.deflate(buf);
                    if (n > 0) {
                        chunk(o, "IDAT", buf, 0, n);
                    }
                }
            }
            chunk(o, "IEND", new byte[0], 0, 0);
            ok = true;
        } finally {
            def.end();
            o.close();
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        }
    }

    private static void chunk(OutputStream o, String type, byte[] d, int off, int len) throws IOException {
        byte[] head = new byte[8];
        be32(head, 0, len);
        byte[] t = type.getBytes("ISO-8859-1");
        System.arraycopy(t, 0, head, 4, 4);
        o.write(head);
        o.write(d, off, len);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(d, off, len);
        byte[] c = new byte[4];
        be32(c, 0, crc.getValue());
        o.write(c);
    }

    private static void be32(byte[] b, int p, long v) {
        b[p] = (byte) (v >> 24);
        b[p + 1] = (byte) (v >> 16);
        b[p + 2] = (byte) (v >> 8);
        b[p + 3] = (byte) v;
    }

    // ---- combining several files ----

    public enum Mode {
        MEAN, SUM, MAX, FOCUS
    }

    /** Opens the files and checks that they fit together (same camera, same size) */
    public static Rows combine(List<File> files, Mode mode) throws IOException {
        if (files.size() < 2) {
            throw new IOException("mindestens 2 Dateien");
        }
        final RawFile[] f = new RawFile[files.size()];
        try {
            for (int i = 0; i < f.length; i++) {
                f[i] = new RawFile(files.get(i));
                if (i > 0 && (f[i].width != f[0].width || f[i].height != f[0].height)) {
                    throw new IOException(files.get(i).getName() + " hat eine andere Größe");
                }
            }
        } catch (IOException e) {
            for (RawFile r : f) {
                if (r != null) {
                    r.close();
                }
            }
            throw e;
        }
        if (mode == Mode.FOCUS) {
            return new FocusStack(f);
        }
        return new Simple(f, mode);
    }

    private static final class Simple implements Rows {
        private final RawFile[] f;
        private final Mode mode;
        private final int[] tmp;

        Simple(RawFile[] f, Mode mode) {
            this.f = f;
            this.mode = mode;
            tmp = new int[f[0].rawWidth];
        }

        @Override
        public RawFile meta() {
            return f[0];
        }

        @Override
        public void row(int y, int[] out) throws IOException {
            int w = f[0].width;
            f[0].readRow(y, out);
            int black = f[0].black[(y & 1) * 2];
            for (int i = 1; i < f.length; i++) {
                f[i].readRow(y, tmp);
                if (nativeOn()) {
                    RawCore.combine(mode == Mode.MAX ? 1 : 0, out, tmp, w);
                    continue;
                }
                switch (mode) {
                    case MAX:
                        for (int x = 0; x < w; x++) {
                            if (tmp[x] > out[x]) {
                                out[x] = tmp[x];
                            }
                        }
                        break;
                    default:
                        for (int x = 0; x < w; x++) {
                            out[x] += tmp[x];
                        }
                        break;
                }
            }
            if (nativeOn() && mode != Mode.MAX) {
                RawCore.finish(mode == Mode.MEAN ? 0 : 1, out, w, f.length, black * (f.length - 1), f[0].white);
                return;
            }
            if (mode == Mode.MEAN) {
                int n = f.length, half = n / 2;
                for (int x = 0; x < w; x++) {
                    out[x] = (out[x] + half) / n;
                }
            } else if (mode == Mode.SUM) {
                // Light adds up, the black level only once
                int lim = f[0].white, sub = black * (f.length - 1);
                for (int x = 0; x < w; x++) {
                    int v = out[x] - sub;
                    out[x] = v > lim ? lim : v < 0 ? 0 : v;
                }
            }
        }

        @Override
        public void close() {
            for (RawFile r : f) {
                r.close();
            }
        }
    }

    /**
     * Focus stacking on the sensor data: per 2x2 block the file with the most local contrast
     * (Laplacian of the block brightness, smoothed over 7x3 blocks). Assumes a tripod: the
     * files are not aligned.
     */
    private static final class FocusStack implements Rows {
        private static final int R = 3;
        private final RawFile[] f;
        private final int n, w, qw, qh;
        /** Per file: CFA rows ring (6), block brightness ring (5), smoothed sharpness ring (3) */
        private final int[][][] cfa, lum, sharp;
        private final int[] tmpL;
        private int nextQ;
        private final int[] odd;
        private int oddFor = -1;
        public int[] picks;

        FocusStack(RawFile[] f) {
            this.f = f;
            n = f.length;
            w = f[0].width;
            qw = w / 2;
            qh = f[0].height / 2;
            cfa = new int[n][6][f[0].rawWidth];
            lum = new int[n][5][qw];
            sharp = new int[n][3][qw];
            tmpL = new int[qw + 1];
            odd = new int[f[0].rawWidth];
            picks = new int[n];
        }

        @Override
        public RawFile meta() {
            return f[0];
        }

        private void readQuad(int s, int q) throws IOException {
            int qc = Math.max(0, Math.min(qh - 1, q));
            // Two CFA rows per block row, stored at slots (q mod 3)*2 and +1
            int slot = ((q % 3) + 3) % 3 * 2;
            int[] a = cfa[s][slot];
            int[] b = cfa[s][slot + 1];
            f[s].readRow(qc * 2, a);
            f[s].readRow(qc * 2 + 1, b);
            int[] l = lum[s][((q % 5) + 5) % 5];
            for (int j = 0; j < qw; j++) {
                l[j] = a[2 * j] + a[2 * j + 1] + b[2 * j] + b[2 * j + 1];
            }
        }

        /** Smoothed sharpness of block row k (needs brightness rows k-1..k+1) */
        private void sharpRow(int s, int k) {
            int[] lm = lum[s][((k - 1) % 5 + 5) % 5], l0 = lum[s][(k % 5 + 5) % 5], lp = lum[s][((k + 1) % 5 + 5) % 5];
            if (k - 1 < 0) {
                lm = l0;
            }
            if (k + 1 >= qh) {
                lp = l0;
            }
            tmpL[0] = 0;
            for (int j = 0; j < qw; j++) {
                int left = l0[j > 0 ? j - 1 : j], right = l0[j < qw - 1 ? j + 1 : j];
                int v = 4 * l0[j] - left - right - lm[j] - lp[j];
                tmpL[j + 1] = tmpL[j] + (v < 0 ? -v : v);
            }
            int[] out = sharp[s][(k % 3 + 3) % 3];
            for (int j = 0; j < qw; j++) {
                int lo = Math.max(0, j - R), hi = Math.min(qw, j + R + 1);
                out[j] = tmpL[hi] - tmpL[lo];
            }
        }

        @Override
        public void row(int y, int[] out) throws IOException {
            int q = y / 2;
            if ((y & 1) == 1 && oddFor == q) {
                System.arraycopy(odd, 0, out, 0, w);
                return;
            }
            // Advance the pipeline until block row q can be decided
            while (nextQ <= q + 2) {
                for (int s = 0; s < n; s++) {
                    readQuad(s, nextQ);
                    if (nextQ - 1 >= 0) {
                        sharpRow(s, nextQ - 1);
                    }
                }
                nextQ++;
            }
            int even = ((q % 3) + 3) % 3 * 2;
            for (int j = 0; j < qw; j++) {
                int best = 0;
                long bestV = -1;
                for (int s = 0; s < n; s++) {
                    long v = (long) sharp[s][((q - 1) % 3 + 3) % 3][j] * (q > 0 ? 1 : 0)
                            + sharp[s][(q % 3 + 3) % 3][j]
                            + (q + 1 < qh ? sharp[s][((q + 1) % 3 + 3) % 3][j] : 0);
                    if (v > bestV) {
                        bestV = v;
                        best = s;
                    }
                }
                picks[best]++;
                int[] a = cfa[best][even], b = cfa[best][even + 1];
                out[2 * j] = a[2 * j];
                out[2 * j + 1] = a[2 * j + 1];
                odd[2 * j] = b[2 * j];
                odd[2 * j + 1] = b[2 * j + 1];
            }
            for (int x = qw * 2; x < w; x++) {
                out[x] = cfa[0][even][x];
                odd[x] = cfa[0][even + 1][x];
            }
            oddFor = q;
            if ((y & 1) == 1) {
                System.arraycopy(odd, 0, out, 0, w);
            }
        }

        @Override
        public void close() {
            for (RawFile r : f) {
                r.close();
            }
        }
    }
}
