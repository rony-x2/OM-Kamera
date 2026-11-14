package com.github.ronyx2.omkamera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes HEIC (HEVC) and AVIF (AV1) files from an ARW (proof of concept). The image is
 * developed like the TIFF (sRGB, 8 bit here), encoded natively in tiles of 512 x 512 (as
 * phones do for HEIC) and put into a HEIF container with an image grid, colour information
 * and the EXIF data incl. the lens. Pure Java container writing (ISO/IEC 23008-12).
 */
public final class Heif {

    public static final int HEIC = 0, AVIF = 1;
    static final int TILE = 512;

    private Heif() {
    }

    private static boolean loaded;

    /** The encoders live in their own library (3 MB); loaded on first use */
    public static synchronized void load() {
        if (!loaded) {
            System.loadLibrary("omkheif");
            loaded = true;
        }
    }

    public static void write(File out, RawTools.Rows src, final int format, int quality, int threads,
                             TiffOut.Lens lens, String software, RawTools.Progress p) throws IOException {
        write(out, src, format, quality, threads, 1.0, lens, software, p);
    }

    /** scale 1 = full size, 0.7071 = half the pixels (12 MP), 0.5 = a quarter (6 MP) */
    public static void write(File out, RawTools.Rows src, final int format, int quality, int threads, double scale,
                             TiffOut.Lens lens, String software, RawTools.Progress p) throws IOException {
        load();
        RawFile m = src.meta();
        final int wIn = m.cropW, hIn = m.cropH;
        final boolean scaled = scale < 0.999;
        final int w = scaled ? (int) Math.round(wIn * scale) & ~1 : wIn;
        final int h = scaled ? (int) Math.round(hIn * scale) & ~1 : hIn;
        final int cols = (w + TILE - 1) / TILE, rows = (h + TILE - 1) / TILE;
        final int n = cols * rows;
        byte[] exif = TiffOut.exifBlock(m, lens, software, w, h);

        // Item IDs: 1..n tiles, n+1 grid, n+2 Exif
        final int gridId = n + 1, exifId = n + 2;
        final long[] off = new long[n + 3];
        final long[] len = new long[n + 3];
        byte[] grid = {0, 0, (byte) (rows - 1), (byte) (cols - 1), (byte) (w >> 8), (byte) w, (byte) (h >> 8), (byte) h};

        final long handle = RawCore.heifNew(format, w, TILE, quality, threads);
        if (handle == 0) {
            throw new IOException("Encoder konnte nicht angelegt werden");
        }
        if (out.exists() && !out.delete()) {
            throw new IOException("kann " + out.getName() + " nicht ersetzen");
        }
        final RandomAccessFile f = new RandomAccessFile(out, "rw");
        boolean ok = false;
        try {
            // Placeholder meta of the final size (sizes do not depend on the offsets)
            // Placeholder with room for the configuration box; the rest is filled with a 'free' box
            byte[] head = header(format, w, h, cols, rows, gridId, exifId, off, len, new byte[0]);
            int reserve = head.length + 1024;
            f.write(new byte[reserve]);
            long mdatStart = f.getFilePointer();
            f.write(new byte[]{0, 0, 0, 0, 'm', 'd', 'a', 't'});
            off[gridId] = f.getFilePointer();
            len[gridId] = grid.length;
            f.write(grid);
            off[exifId] = f.getFilePointer();
            byte[] ex = new byte[4 + exif.length];
            System.arraycopy(exif, 0, ex, 4, exif.length);
            len[exifId] = ex.length;
            f.write(ex);

            final int[] band = {0, 0};
            final int stride = w * 3;
            final RawTools.ByteSink toBand = new RawTools.ByteSink() {
                @Override
                public void write(byte[] b, int l) throws IOException {
                    int r = l / stride;
                    int pos = 0;
                    while (r > 0) {
                        int take = Math.min(r, TILE - band[1]);
                        byte[] part = pos == 0 && take * stride == l ? b : java.util.Arrays.copyOfRange(b, pos, pos + take * stride);
                        RawCore.heifRows(handle, part, band[1], take);
                        band[1] += take;
                        pos += take * stride;
                        r -= take;
                        if (band[1] == TILE || (band[0] * TILE + band[1]) == h) {
                            flush(f, handle, format, band[0], band[1], cols, off, len);
                            band[0]++;
                            band[1] = 0;
                        }
                    }
                }
            };
            RawTools.develop(src, 3, scaled ? new Scaler(wIn, hIn, w, h, toBand) : toBand, p);
            if (band[1] > 0) {
                flush(f, handle, format, band[0], band[1], cols, off, len);
            }
            long end = f.getFilePointer();
            byte[] cfg = RawCore.heifConfig(handle);
            if (cfg.length == 0) {
                throw new IOException("keine Encoder-Konfiguration erhalten");
            }
            byte[] finalHead = header(format, w, h, cols, rows, gridId, exifId, off, len, cfg);
            int pad = reserve - finalHead.length;
            if (pad < 8) {
                throw new IOException("Kopf zu groß (" + finalHead.length + " > " + reserve + ")");
            }
            f.seek(0);
            f.write(finalHead);
            f.write(new byte[]{(byte) (pad >> 24), (byte) (pad >> 16), (byte) (pad >> 8), (byte) pad, 'f', 'r', 'e', 'e'});
            f.seek(mdatStart);
            int ms = (int) (end - mdatStart);
            f.write(new byte[]{(byte) (ms >> 24), (byte) (ms >> 16), (byte) (ms >> 8), (byte) ms});
            ok = true;
        } finally {
            RawCore.heifFree(handle);
            f.close();
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        }
    }

    private static void flush(RandomAccessFile f, long handle, int format, int bandIndex, int rowsInBand, int cols,
                              long[] off, long[] len) throws IOException {
        int err = RawCore.heifEncode(handle, rowsInBand);
        if (err != 0) {
            throw new IOException((format == HEIC ? "HEVC" : "AV1") + "-Kodierung fehlgeschlagen (" + err + ")");
        }
        for (int c = 0; c < cols; c++) {
            byte[] t = RawCore.heifTile(handle, c);
            int id = bandIndex * cols + c + 1;
            off[id] = f.getFilePointer();
            len[id] = t.length;
            f.write(t);
        }
    }

    /**
     * Downscales developed rows (8 bit RGB) on the fly: output row k lies at source position
     * (k + 0.5) / s - 0.5 and is interpolated from the two neighbouring source rows.
     */
    static final class Scaler implements RawTools.ByteSink {
        private final int wIn, hIn, wOut, hOut;
        private final RawTools.ByteSink sink;
        private byte[] prev, cur;
        private final byte[] outRow;
        private int y = -1, k = 0;

        Scaler(int wIn, int hIn, int wOut, int hOut, RawTools.ByteSink sink) {
            this.wIn = wIn;
            this.hIn = hIn;
            this.wOut = wOut;
            this.hOut = hOut;
            this.sink = sink;
            outRow = new byte[wOut * 3];
        }

        private double srcY(int row) {
            return Math.max(0, (row + 0.5) * hIn / hOut - 0.5);
        }

        @Override
        public void write(byte[] b, int len) throws IOException {
            int stride = wIn * 3;
            for (int off = 0; off < len; off += stride) {
                prev = cur;
                cur = java.util.Arrays.copyOfRange(b, off, off + stride);
                y++;
                // Output rows between source rows y-1 and y
                while (k < hOut && (srcY(k) <= y || y == hIn - 1)) {
                    double sy = srcY(k);
                    byte[] a = prev != null && sy < y ? prev : cur;
                    int fy = prev != null && sy < y ? (int) Math.round((sy - (y - 1)) * 256) : 0;
                    RawCore.scaleRow(a, cur, Math.max(0, Math.min(256, fy)), wIn, wOut, outRow);
                    sink.write(outRow, outRow.length);
                    k++;
                }
            }
        }
    }

    // ---- boxes ----

    private static byte[] header(int format, int w, int h, int cols, int rows, int gridId, int exifId,
                                 long[] off, long[] len, byte[] cfg) {
        int n = cols * rows;
        String tileType = format == HEIC ? "hvc1" : "av01";
        Box ftyp = new Box("ftyp");
        if (format == HEIC) {
            ftyp.str("heic").u32(0).str("mif1").str("heic");
        } else {
            ftyp.str("avif").u32(0).str("avif").str("mif1").str("miaf");
        }

        Box hdlr = full("hdlr", 0, 0).u32(0).str("pict").u32(0).u32(0).u32(0).u8(0);
        Box pitm = full("pitm", 0, 0).u16(gridId);

        Box iloc = full("iloc", 0, 0).u8(0x44).u8(0x00).u16(n + 2);
        for (int id = 1; id <= n + 2; id++) {
            iloc.u16(id).u16(0).u16(1).u32(off[id]).u32(len[id]);
        }

        Box iinf = full("iinf", 0, 0).u16(n + 2);
        for (int id = 1; id <= n; id++) {
            // Tiles are hidden: only the grid is the picture
            iinf.add(full("infe", 2, 1).u16(id).u16(0).str(tileType).u8(0));
        }
        iinf.add(full("infe", 2, 0).u16(gridId).u16(0).str("grid").u8(0));
        iinf.add(full("infe", 2, 0).u16(exifId).u16(0).str("Exif").u8(0));

        Box dimg = new Box("dimg").u16(gridId).u16(n);
        for (int id = 1; id <= n; id++) {
            dimg.u16(id);
        }
        Box cdsc = new Box("cdsc").u16(exifId).u16(1).u16(gridId);
        Box iref = full("iref", 0, 0).add(dimg).add(cdsc);

        Box ipco = new Box("ipco");
        ipco.add(new Box(format == HEIC ? "hvcC" : "av1C").raw(cfg));                // 1
        ipco.add(full("ispe", 0, 0).u32(TILE).u32(TILE));                            // 2
        ipco.add(full("ispe", 0, 0).u32(w).u32(h));                                  // 3
        ipco.add(new Box("colr").str("nclx").u16(1).u16(13).u16(6).u8(0x80));        // 4 sRGB, BT.601, full range
        ipco.add(full("pixi", 0, 0).u8(3).u8(8).u8(8).u8(8));                        // 5
        Box ipma = full("ipma", 0, 0).u32(n + 1);
        for (int id = 1; id <= n; id++) {
            ipma.u16(id).u8(3).u8(0x81).u8(2).u8(5);
        }
        ipma.u16(gridId).u8(3).u8(3).u8(4).u8(5);
        Box iprp = new Box("iprp").add(ipco).add(ipma);

        Box meta = full("meta", 0, 0).add(hdlr).add(pitm).add(iloc).add(iinf).add(iref).add(iprp);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] a = ftyp.bytes(), b = meta.bytes();
        o.write(a, 0, a.length);
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    private static Box full(String type, int version, int flags) {
        return new Box(type).u8(version).u8(flags >> 16).u16(flags & 0xFFFF);
    }

    /** Minimal ISO base media box builder */
    static final class Box {
        private final String type;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final List<Box> children = new ArrayList<Box>();

        Box(String type) {
            this.type = type;
        }

        Box u8(int v) {
            flushChildren();
            body.write(v);
            return this;
        }

        Box u16(int v) {
            return u8(v >> 8).u8(v);
        }

        Box u32(long v) {
            return u16((int) (v >> 16) & 0xFFFF).u16((int) v & 0xFFFF);
        }

        Box str(String s) {
            for (int i = 0; i < s.length(); i++) {
                u8(s.charAt(i));
            }
            return this;
        }

        Box raw(byte[] b) {
            flushChildren();
            body.write(b, 0, b.length);
            return this;
        }

        Box add(Box c) {
            children.add(c);
            return this;
        }

        private void flushChildren() {
            for (Box c : children) {
                byte[] b = c.bytes();
                body.write(b, 0, b.length);
            }
            children.clear();
        }

        byte[] bytes() {
            flushChildren();
            byte[] b = body.toByteArray();
            int size = b.length + 8;
            byte[] out = new byte[size];
            out[0] = (byte) (size >> 24);
            out[1] = (byte) (size >> 16);
            out[2] = (byte) (size >> 8);
            out[3] = (byte) size;
            for (int i = 0; i < 4; i++) {
                out[4 + i] = (byte) type.charAt(i);
            }
            System.arraycopy(b, 0, out, 8, b.length);
            return out;
        }
    }
}
