package com.github.ronyx2.omkamera;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Builds little endian TIFF directories for DNG, TIFF and the EXIF block of PNG files.
 * Directories are laid out in memory (header, IFDs, values); the image data follows behind
 * and is streamed by the caller, so its offset has to be known in advance.
 */
public class TiffOut {

    public static final int BYTE = 1, ASCII = 2, SHORT = 3, LONG = 4, RATIONAL = 5, UNDEFINED = 7, SRATIONAL = 10;

    public static final class Dir {
        final List<RawFile.Entry> entries = new ArrayList<RawFile.Entry>();
        /** Tag of a pointer entry -> directory it points to */
        final List<Object[]> children = new ArrayList<Object[]>();
        int offset;

        public Dir put(int tag, int type, int count, byte[] data) {
            RawFile.Entry n = new RawFile.Entry(tag, type, count, data);
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).tag == tag) {
                    // Replace in place: layout() relies on the order and sizes staying put
                    entries.set(i, n);
                    return this;
                }
            }
            entries.add(n);
            return this;
        }

        public Dir remove(int tag) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).tag == tag) {
                    entries.remove(i);
                }
            }
            return this;
        }

        public boolean has(int tag) {
            for (RawFile.Entry e : entries) {
                if (e.tag == tag) {
                    return true;
                }
            }
            return false;
        }

        public Dir shorts(int tag, int... v) {
            byte[] b = new byte[v.length * 2];
            for (int i = 0; i < v.length; i++) {
                b[i * 2] = (byte) v[i];
                b[i * 2 + 1] = (byte) (v[i] >> 8);
            }
            return put(tag, SHORT, v.length, b);
        }

        public Dir longs(int tag, long... v) {
            byte[] b = new byte[v.length * 4];
            for (int i = 0; i < v.length; i++) {
                le32(b, i * 4, v[i]);
            }
            return put(tag, LONG, v.length, b);
        }

        public Dir bytes(int tag, int... v) {
            byte[] b = new byte[v.length];
            for (int i = 0; i < v.length; i++) {
                b[i] = (byte) v[i];
            }
            return put(tag, BYTE, v.length, b);
        }

        /** Pairs of numerator, denominator */
        public Dir rationals(int tag, boolean signed, long... nd) {
            byte[] b = new byte[nd.length * 4];
            for (int i = 0; i < nd.length; i++) {
                le32(b, i * 4, nd[i]);
            }
            return put(tag, signed ? SRATIONAL : RATIONAL, nd.length / 2, b);
        }

        public Dir ascii(int tag, String s) {
            byte[] src;
            try {
                src = s.getBytes("ISO-8859-1");
            } catch (java.io.UnsupportedEncodingException e) {
                src = s.getBytes();
            }
            byte[] b = new byte[src.length + 1];
            System.arraycopy(src, 0, b, 0, src.length);
            return put(tag, ASCII, b.length, b);
        }

        /** Entry pointing to a sub directory (EXIF IFD) */
        public Dir child(int tag, Dir d) {
            longs(tag, 0);
            children.add(new Object[]{tag, d});
            return this;
        }
    }

    static void le32(byte[] b, int p, long v) {
        b[p] = (byte) v;
        b[p + 1] = (byte) (v >> 8);
        b[p + 2] = (byte) (v >> 16);
        b[p + 3] = (byte) (v >> 24);
    }

    /**
     * Lays out the header and the directories (root first, then its children) and returns the
     * bytes. The image data starts directly behind, at the returned array's length.
     *
     * @param dataOffsetTag strip offset tag of the root that gets the data offset, or 0
     */
    public static byte[] layout(Dir root, int dataOffsetTag) {
        if (dataOffsetTag != 0) {
            root.longs(dataOffsetTag, 0);
        }
        List<Dir> all = new ArrayList<Dir>();
        collect(root, all);
        for (Dir d : all) {
            Collections.sort(d.entries, new Comparator<RawFile.Entry>() {
                @Override
                public int compare(RawFile.Entry a, RawFile.Entry b) {
                    return a.tag - b.tag;
                }
            });
        }
        // Pass 1: offsets of the directories and their value areas
        int pos = 8;
        int[] valueStart = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Dir d = all.get(i);
            d.offset = pos;
            pos += 2 + d.entries.size() * 12 + 4;
            valueStart[i] = pos;
            for (RawFile.Entry e : d.entries) {
                if (e.data.length > 4) {
                    pos += (e.data.length + 1) & ~1;
                }
            }
        }
        int dataOffset = (pos + 3) & ~3;
        // Pointers to children and to the image data
        for (Dir d : all) {
            for (Object[] c : d.children) {
                d.longs((Integer) c[0], ((Dir) c[1]).offset);
            }
        }
        if (dataOffsetTag != 0) {
            root.longs(dataOffsetTag, dataOffset);
        }
        // Pass 2: write
        byte[] out = new byte[dataOffset];
        out[0] = 'I';
        out[1] = 'I';
        out[2] = 42;
        le32(out, 4, root.offset);
        for (int i = 0; i < all.size(); i++) {
            Dir d = all.get(i);
            int p = d.offset;
            out[p] = (byte) d.entries.size();
            out[p + 1] = (byte) (d.entries.size() >> 8);
            p += 2;
            int v = valueStart[i];
            for (RawFile.Entry e : d.entries) {
                out[p] = (byte) e.tag;
                out[p + 1] = (byte) (e.tag >> 8);
                out[p + 2] = (byte) e.type;
                out[p + 3] = (byte) (e.type >> 8);
                le32(out, p + 4, e.count);
                if (e.data.length <= 4) {
                    System.arraycopy(e.data, 0, out, p + 8, e.data.length);
                } else {
                    le32(out, p + 8, v);
                    System.arraycopy(e.data, 0, out, v, e.data.length);
                    v += (e.data.length + 1) & ~1;
                }
                p += 12;
            }
            le32(out, p, 0);
        }
        return out;
    }

    private static void collect(Dir d, List<Dir> all) {
        all.add(d);
        for (Object[] c : d.children) {
            collect((Dir) c[1], all);
        }
    }

    // ---- metadata shared by DNG, TIFF and PNG ----

    /** Lens data known to the app at capture time, used when the file itself has none */
    public static final class Lens {
        public String name;
        /** focal min/max in mm, f-number at min/max focal length; 0 if unknown */
        public double focalMin, focalMax, fMin, fMax;
    }

    /**
     * Copies the descriptive tags of the source: make, model, date, orientation, artist and
     * copyright into the root, and the EXIF directory without the maker note. Lens model and
     * lens specification come from the file, or from the app's lens data if the file has none.
     */
    public static Dir metadata(RawFile src, Dir root, Lens lens, String software) {
        root.ascii(0x010f, src.make.length() > 0 ? src.make : "SONY");
        root.ascii(0x0110, src.model);
        for (int tag : new int[]{0x0132, 0x013b, 0x8298}) {
            RawFile.Entry e = src.ifd0.get(tag);
            if (e != null) {
                root.put(tag, e.type, e.count, src.littleData(e));
            }
        }
        root.shorts(0x0112, src.orientation);
        root.ascii(0x0131, software);
        Dir exif = new Dir();
        for (RawFile.Entry e : src.exifEntries()) {
            if (e.tag == 0x927c || e.tag == 0xa005 || e.tag == 0x9286) {
                // Maker note and interoperability pointer refer to offsets in the old file
                continue;
            }
            exif.put(e.tag, e.type, e.count, src.littleData(e));
        }
        if (lens != null) {
            if (!exif.has(0xa434) && lens.name != null && lens.name.length() > 0) {
                exif.ascii(0xa434, lens.name);
            }
            if (!exif.has(0xa432) && lens.focalMin > 0) {
                exif.rationals(0xa432, false, r(lens.focalMin), 10, r(lens.focalMax), 10,
                        r(lens.fMin), 10, r(lens.fMax), 10);
            }
        }
        root.child(0x8769, exif);
        return exif;
    }

    private static long r(double v) {
        return Math.round(v * 10);
    }

    /** Lens specification of the EXIF directory as DNG LensInfo (same layout) */
    public static void lensInfo(Dir root, Dir exif) {
        for (RawFile.Entry e : exif.entries) {
            if (e.tag == 0xa432 && e.type == RATIONAL && e.count == 4) {
                root.put(50736, RATIONAL, 4, e.data);
            }
        }
    }

    /** A standalone EXIF block (TIFF header + directories) for the PNG eXIf chunk */
    public static byte[] exifBlock(RawFile src, Lens lens, String software) {
        return exifBlock(src, lens, software, 0, 0);
    }

    /** As above; width and height > 0 replace the pixel size in the EXIF data (scaled output) */
    public static byte[] exifBlock(RawFile src, Lens lens, String software, int width, int height) {
        Dir root = new Dir();
        Dir exif = metadata(src, root, lens, software);
        if (width > 0 && height > 0) {
            exif.longs(0xa002, width);
            exif.longs(0xa003, height);
        }
        return layout(root, 0);
    }

    static void writeAll(OutputStream o, byte[] b) throws IOException {
        o.write(b);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            b.write(p, 0, p.length);
        }
        return b.toByteArray();
    }
}
