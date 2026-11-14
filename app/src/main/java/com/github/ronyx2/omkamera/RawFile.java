package com.github.ronyx2.omkamera;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a Sony ARW file row by row: the TIFF structure, the metadata needed for DNG and
 * development (black and white level, white balance, CFA pattern, tone curve) and the sensor
 * data. Supports Sony's compressed RAW (compression 32767, 8 bits per pixel on the card) and
 * uncompressed 16 bit containers. Pure Java, no Android classes, so it can be tested on a PC.
 *
 * Format knowledge from public descriptions (dcraw/LibRaw, ExifTool documentation).
 */
public class RawFile {

    /** One TIFF directory entry, value bytes in file byte order */
    public static final class Entry {
        public final int tag, type, count;
        public final byte[] data;

        Entry(int tag, int type, int count, byte[] data) {
            this.tag = tag;
            this.type = type;
            this.count = count;
            this.data = data;
        }
    }

    public final File file;
    private final RandomAccessFile in;
    private final boolean little;

    public final Map<Integer, Entry> ifd0 = new LinkedHashMap<Integer, Entry>();
    public final Map<Integer, Entry> exif = new LinkedHashMap<Integer, Entry>();
    public final Map<Integer, Entry> rawIfd = new LinkedHashMap<Integer, Entry>();
    public final Map<Integer, Entry> sr2 = new LinkedHashMap<Integer, Entry>();

    public String make = "", model = "";
    public int orientation = 1;
    /** Size of the stored sensor data */
    public int rawWidth, rawHeight;
    /** Columns that hold real pixels (the rest is padding) */
    public int width, height;
    /** Visible area as written in the EXIF data (e.g. 6000 x 4000) */
    public int cropX, cropY, cropW, cropH;
    public int compression, bitsPerSample;
    private long dataOffset;
    /** Colour of the CFA positions: 0 red, 1 green, 2 blue; index (y&1)*2+(x&1) */
    public final int[] cfa = {0, 1, 1, 2};
    public final int[] black = {512, 512, 512, 512};
    public int white = 16383;
    /** White balance multipliers R, G, B (G = 1) */
    public final double[] wb = {2, 1, 1.5};
    private int[] curve;
    private byte[] rowBuf;

    public RawFile(File f) throws IOException {
        this.file = f;
        in = new RandomAccessFile(f, "r");
        byte[] h = new byte[8];
        in.readFully(h);
        if (h[0] == 'I' && h[1] == 'I') {
            little = true;
        } else if (h[0] == 'M' && h[1] == 'M') {
            little = false;
        } else {
            in.close();
            throw new IOException("keine TIFF/ARW-Datei");
        }
        long first = u32(h, 4);
        readIfd(first, ifd0, null, 0);
        Entry e;
        if ((e = ifd0.get(0x8769)) != null) {
            readIfd(u32(e.data, 0), exif, null, 0);
        }
        if ((e = ifd0.get(0x014a)) != null) {
            // Sony keeps the RAW image in the first SubIFD
            readIfd(u32(e.data, 0), rawIfd, null, 0);
        }
        if (rawIfd.isEmpty()) {
            in.close();
            throw new IOException("keine RAW-Daten gefunden");
        }
        if ((e = ifd0.get(0xc634)) != null) {
            readSr2(u32(e.data, 0));
        }
        make = ascii(ifd0.get(0x010f));
        model = ascii(ifd0.get(0x0110));
        orientation = Math.max(1, num(ifd0.get(0x0112), 0, 1));

        rawWidth = num(rawIfd.get(0x0100), 0, 0);
        rawHeight = num(rawIfd.get(0x0101), 0, 0);
        compression = num(rawIfd.get(0x0103), 0, 1);
        bitsPerSample = num(rawIfd.get(0x0102), 0, 16);
        dataOffset = num(rawIfd.get(0x0111), 0, 0) & 0xFFFFFFFFL;
        if ((e = rawIfd.get(0x828e)) != null && e.data.length >= 4) {
            for (int i = 0; i < 4; i++) {
                cfa[i] = e.data[i] & 3;
            }
        }
        // Visible size from the EXIF data; Sony stores some extra columns on the right
        int ew = num(exif.get(0xa002), 0, rawWidth);
        int eh = num(exif.get(0xa003), 0, rawHeight);
        width = Math.min(rawWidth, ew + 24);
        height = rawHeight;
        cropW = Math.min(ew, width);
        cropH = Math.min(eh, height);
        // Even offsets keep the CFA phase
        cropX = ((width - cropW) / 2) & ~1;
        cropY = ((height - cropH) / 2) & ~1;

        if ((e = sr2.get(0x7310)) != null) {
            for (int i = 0; i < 4; i++) {
                black[i] = num(e, i, 512);
            }
        }
        if ((e = sr2.get(0x787f)) != null) {
            white = num(e, 0, 16383);
        }
        int[] lv = null;
        if ((e = sr2.get(0x7313)) != null) {
            lv = new int[]{num(e, 0, 0), num(e, 1, 0), num(e, 3, 0)}; // R G G B
        } else if ((e = sr2.get(0x7303)) != null) {
            lv = new int[]{num(e, 1, 0), num(e, 0, 0), num(e, 2, 0)}; // G R B G
        }
        if (lv != null && lv[0] > 0 && lv[1] > 0 && lv[2] > 0) {
            wb[0] = (double) lv[0] / lv[1];
            wb[1] = 1;
            wb[2] = (double) lv[2] / lv[1];
        }
        buildCurve();
        if (compression == 32767) {
            rowBuf = new byte[rawWidth + 16];
        } else if (compression == 1) {
            rowBuf = new byte[rawWidth * 2];
        } else {
            in.close();
            throw new IOException("RAW-Kompression " + compression + " wird nicht unterstützt");
        }
    }

    public void close() {
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    // ---- sensor data ----

    /** Reads one row of sensor values (curve applied, black level not subtracted) into out[0..width) */
    public void readRow(int row, int[] out) throws IOException {
        if (compression == 32767) {
            in.seek(dataOffset + (long) row * rawWidth);
            in.readFully(rowBuf, 0, rawWidth);
            decodeArw2(rowBuf, out);
        } else {
            in.seek(dataOffset + (long) row * rawWidth * 2);
            in.readFully(rowBuf, 0, rawWidth * 2);
            for (int c = 0; c < width; c++) {
                int i = c * 2;
                out[c] = little ? (rowBuf[i] & 0xFF) | (rowBuf[i + 1] & 0xFF) << 8
                        : (rowBuf[i] & 0xFF) << 8 | (rowBuf[i + 1] & 0xFF);
            }
        }
    }

    /**
     * Sony compressed RAW: blocks of 16 bytes hold 16 pixels of the same colour (every other
     * column): an 11 bit maximum and minimum, their positions, and 14 deltas of 7 bits.
     */
    private void decodeArw2(byte[] d, int[] out) {
        int[] pix = new int[16];
        int col = 0;
        for (int dp = 0; col < rawWidth - 30; dp += 16) {
            int val = (d[dp] & 0xFF) | (d[dp + 1] & 0xFF) << 8 | (d[dp + 2] & 0xFF) << 16 | (d[dp + 3] & 0xFF) << 24;
            int max = val & 0x7FF;
            int min = (val >>> 11) & 0x7FF;
            int imax = (val >>> 22) & 0x0F;
            int imin = (val >>> 26) & 0x0F;
            int sh = 0;
            while (sh < 4 && (0x80 << sh) <= max - min) {
                sh++;
            }
            int bit = 30;
            for (int i = 0; i < 16; i++) {
                if (i == imax) {
                    pix[i] = max;
                } else if (i == imin) {
                    pix[i] = min;
                } else {
                    int p = dp + (bit >> 3);
                    int w = (d[p] & 0xFF) | (d[p + 1] & 0xFF) << 8;
                    int v = (((w >>> (bit & 7)) & 0x7F) << sh) + min;
                    pix[i] = v > 0x7FF ? 0x7FF : v;
                    bit += 7;
                }
            }
            for (int i = 0; i < 16; i++, col += 2) {
                if (col < width) {
                    out[col] = curve[pix[i] << 1];
                }
            }
            col -= (col & 1) != 0 ? 1 : 31;
        }
    }

    private void buildCurve() {
        curve = new int[0x1000];
        for (int i = 0; i < curve.length; i++) {
            curve[i] = i;
        }
        Entry e = rawIfd.get(0x7010);
        if (e == null || compression != 32767) {
            return;
        }
        int[] pts = {0, 0, 0, 0, 0, 4095};
        for (int i = 0; i < 4; i++) {
            pts[i + 1] = (num(e, i, 0) >> 2) & 0xFFF;
        }
        for (int i = 0; i < 5; i++) {
            for (int j = pts[i] + 1; j <= pts[i + 1] && j < curve.length; j++) {
                curve[j] = curve[j - 1] + (1 << i);
            }
        }
    }

    // ---- TIFF structure ----

    private void readIfd(long off, Map<Integer, Entry> into, byte[] mem, long base) throws IOException {
        byte[] b2 = new byte[2];
        read(mem, base, off, b2);
        int n = u16(b2, 0);
        if (n <= 0 || n > 1000) {
            return;
        }
        byte[] dir = new byte[n * 12];
        read(mem, base, off + 2, dir);
        for (int i = 0; i < n; i++) {
            int p = i * 12;
            int tag = u16(dir, p);
            int type = u16(dir, p + 2);
            int count = (int) u32(dir, p + 4);
            int size = typeSize(type) * count;
            if (size < 0 || size > 4 * 1024 * 1024) {
                continue;
            }
            byte[] data = new byte[size];
            if (size <= 4) {
                System.arraycopy(dir, p + 8, data, 0, size);
            } else {
                try {
                    read(mem, base, u32(dir, p + 8), data);
                } catch (IOException e) {
                    continue;
                }
            }
            into.put(tag, new Entry(tag, type, count, data));
        }
    }

    private void read(byte[] mem, long base, long off, byte[] out) throws IOException {
        if (mem == null) {
            in.seek(off);
            in.readFully(out);
        } else {
            int p = (int) (off - base);
            if (p < 0 || p + out.length > mem.length) {
                throw new IOException("außerhalb");
            }
            System.arraycopy(mem, p, out, 0, out.length);
        }
    }

    /** Sony's private block (white balance, black level) is encrypted with a simple key stream */
    private void readSr2(long privOff) {
        try {
            Map<Integer, Entry> priv = new LinkedHashMap<Integer, Entry>();
            readIfd(privOff, priv, null, 0);
            Entry eo = priv.get(0x7200), el = priv.get(0x7201), ek = priv.get(0x7221);
            if (eo == null || el == null || ek == null) {
                return;
            }
            long off = u32(eo.data, 0);
            int len = (int) u32(el.data, 0);
            int key = (int) u32(ek.data, 0);
            if (len <= 0 || len > 1024 * 1024) {
                return;
            }
            byte[] buf = new byte[len];
            in.seek(off);
            in.readFully(buf);
            decrypt(buf, key);
            readIfd(off, sr2, buf, off);
        } catch (IOException ignored) {
        }
    }

    static void decrypt(byte[] buf, int key) {
        int[] pad = new int[128];
        for (int p = 0; p < 4; p++) {
            key = key * 48828125 + 1;
            pad[p] = key;
        }
        pad[3] = pad[3] << 1 | (pad[0] ^ pad[2]) >>> 31;
        for (int p = 4; p < 127; p++) {
            pad[p] = (pad[p - 4] ^ pad[p - 2]) << 1 | (pad[p - 3] ^ pad[p - 1]) >>> 31;
        }
        int p = 127;
        for (int i = 0; i + 3 < buf.length; i += 4) {
            p++;
            int v = pad[p & 127] ^ pad[(p + 64) & 127];
            pad[(p - 1) & 127] = v;
            // The key stream is applied in big endian byte order
            buf[i] ^= (byte) (v >>> 24);
            buf[i + 1] ^= (byte) (v >>> 16);
            buf[i + 2] ^= (byte) (v >>> 8);
            buf[i + 3] ^= (byte) v;
        }
    }

    // ---- helpers ----

    public boolean littleEndian() {
        return little;
    }

    static int typeSize(int type) {
        switch (type) {
            case 3: case 8: return 2;
            case 4: case 9: case 11: case 13: return 4;
            case 5: case 10: case 12: return 8;
            default: return 1;
        }
    }

    int u16(byte[] b, int p) {
        return little ? (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 : (b[p] & 0xFF) << 8 | (b[p + 1] & 0xFF);
    }

    long u32(byte[] b, int p) {
        return little
                ? ((b[p] & 0xFFL) | (b[p + 1] & 0xFFL) << 8 | (b[p + 2] & 0xFFL) << 16 | (b[p + 3] & 0xFFL) << 24)
                : ((b[p] & 0xFFL) << 24 | (b[p + 1] & 0xFFL) << 16 | (b[p + 2] & 0xFFL) << 8 | (b[p + 3] & 0xFFL));
    }

    /** Value i of a SHORT/LONG/BYTE entry */
    public int num(Entry e, int i, int def) {
        if (e == null || i >= e.count) {
            return def;
        }
        switch (e.type) {
            case 3: case 8: return u16(e.data, i * 2);
            case 4: case 9: case 13: return (int) u32(e.data, i * 4);
            case 1: case 6: case 7: return e.data[i] & 0xFF;
            default: return def;
        }
    }

    /** Numerator and denominator of a RATIONAL entry */
    public long[] rational(Entry e, int i) {
        if (e == null || i >= e.count || (e.type != 5 && e.type != 10)) {
            return null;
        }
        return new long[]{u32(e.data, i * 8), u32(e.data, i * 8 + 4)};
    }

    public static String ascii(Entry e) {
        if (e == null) {
            return "";
        }
        int n = 0;
        while (n < e.data.length && e.data[n] != 0) {
            n++;
        }
        try {
            return new String(e.data, 0, n, "ISO-8859-1").trim();
        } catch (java.io.UnsupportedEncodingException x) {
            return "";
        }
    }

    /** Entry data converted to little endian, for writing into a new file */
    public byte[] littleData(Entry e) {
        if (little) {
            return e.data;
        }
        int unit = e.type == 5 || e.type == 10 ? 4 : typeSize(e.type);
        byte[] out = e.data.clone();
        if (unit > 1) {
            for (int p = 0; p + unit <= out.length; p += unit) {
                for (int k = 0; k < unit / 2; k++) {
                    byte t = out[p + k];
                    out[p + k] = out[p + unit - 1 - k];
                    out[p + unit - 1 - k] = t;
                }
            }
        }
        return out;
    }

    /** Short description for logs and the MCP */
    public String describe() {
        return model + " " + rawWidth + "x" + rawHeight + " (Bild " + cropW + "x" + cropH + ") comp=" + compression
                + " schwarz=" + black[0] + " weiß=" + white
                + String.format(java.util.Locale.US, " WB=%.3f/%.3f", wb[0], wb[2])
                + " Objektiv=" + ascii(exif.get(0xa434));
    }

    public List<Entry> exifEntries() {
        return new ArrayList<Entry>(exif.values());
    }
}
