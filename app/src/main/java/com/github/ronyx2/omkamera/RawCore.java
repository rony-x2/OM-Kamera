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
}
