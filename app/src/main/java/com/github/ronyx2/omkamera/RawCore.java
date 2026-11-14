package com.github.ronyx2.omkamera;

/**
 * Native ARW row decoder (librawcore.so, built with the NDK from native/jni/rawcore.c).
 * Uses all CPU cores and works outside the Java heap. If the library cannot be loaded, the
 * Java decoder in {@link RawFile} is used.
 */
public final class RawCore {

    public static final boolean AVAILABLE;
    public static final String STATUS;

    static {
        boolean ok = false;
        String st;
        try {
            System.loadLibrary("rawcore");
            st = "nativ, " + cores() + " Kern(e)";
            ok = true;
        } catch (Throwable t) {
            st = "Java (" + t.getClass().getSimpleName() + ")";
        }
        AVAILABLE = ok;
        STATUS = st;
    }

    private RawCore() {
    }

    public static native int cores();

    public static native int open(String path);

    public static native void close(int fd);

    /** Rows y .. y+count-1 into out (count * width values); 0 or an error code */
    public static native int decode(int fd, long offset, int rawWidth, int width, int compression,
                                    boolean little, int[] curve, int y, int count, int[] out);

    /** mode 0: acc += add, 1: acc = max(acc, add) */
    /** Like decode, but adds the rows (mode 0) or takes the maximum (mode 1) into acc */
    public static native int decodeCombine(int fd, long offset, int rawWidth, int width, int compression,
                                           boolean little, int[] curve, int y, int count, int[] acc, int mode);

    /** Measures reading 'rows' rows from the card and decoding them with 1 and n threads */
    public static native String bench(int fd, long offset, int rawWidth, int width, int rows, int[] curve);

    public static native void combine(int mode, int[] acc, int[] add, int n);

    /** mode 0: mean over count, 1: sum minus sub, clipped to 0..lim */
    public static native void finish(int mode, int[] acc, int n, int count, int sub, int lim);

    public static native void pack16(int[] row, int n, byte[] out);

    /** Develops n rows from n + 2 sensor rows; format 0 TIFF (LE), 1 PNG 16 bit, 2 PNG 8 bit (with filter byte) */
    public static native int develop(int[] cfa, int n, int fullW, int x0, int w, int y0, int[] pat, int[] blk,
                                     int[] scale, int[] mat, short[] lut, int format, byte[] out);

    /**
     * Raw deflate of in[0..len) split over all cores; last ends the stream. adler[0] is the
     * running Adler-32 (start with 1). Returns the bytes written to out or a negative error.
     */
    public static native int deflate(byte[] in, int len, int level, boolean last, byte[] out, long[] adler);

    // ---- HEIC/AVIF tile encoder (libomkheif, loaded by Heif.load()) ----

    public static native long heifNew(int format, int width, int tile, int quality, int threads);

    public static native void heifRows(long h, byte[] rows, int at, int n);

    public static native int heifEncode(long h, int rows);

    public static native byte[] heifTile(long h, int col);

    public static native byte[] heifConfig(long h);

    public static native void heifFree(long h);

    /** Bilinear row for downscaling: rows a and b (8 bit RGB), fy weight of b 0..256 */
    public static native void scaleRow(byte[] a, byte[] b, int fy, int wIn, int wOut, byte[] out);

    /** True if the library is loaded; false on the PC tests without it */
    public static boolean on() {
        return AVAILABLE && RawFile.useNative;
    }
}
