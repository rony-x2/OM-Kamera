package com.github.ronyx2.omkamera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * Zebra, focus peaking and the RGB histogram in software: the live view JPEG frames (640 px wide) are decoded at
 * half size on a worker thread, analysed and turned into a small transparent mask that the
 * overlay stretches over the picture. Runs only while one of the two is switched on and the
 * live view is visible; about 3–5 masks per second.
 */
public class LiveAnalysis {

    public interface Host {
        /** Zebra threshold as luma 0..255, 0 = off */
        int zebraLevel();

        /** Peaking threshold for the edge strength, 0 = off */
        int peakingLevel();

        int peakingColor();

        /** RGB histogram wanted (the ILCE-6000's analyser only delivers luma) */
        boolean rgbHistogram();

        /** Analysis only in the live view (not in menus) */
        boolean liveVisible();

        void maskReady();
    }

    private final CameraSession session;
    private final Host host;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile Bitmap mask;
    private volatile long lastMs;
    /** R, G, B histograms with 128 bins each, or null */
    private volatile short[][] rgb;

    public LiveAnalysis(CameraSession session, Host host) {
        this.session = session;
        this.host = host;
    }

    public Bitmap mask() {
        return mask;
    }

    public short[][] rgb() {
        return rgb;
    }

    /** Duration of the last analysis in ms, for the log and MCP */
    public long lastMs() {
        return lastMs;
    }

    public synchronized void update() {
        boolean want = host.zebraLevel() > 0 || host.peakingLevel() > 0 || host.rgbHistogram();
        if (want && !running) {
            running = true;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "live-analysis");
            thread.setPriority(Thread.MIN_PRIORITY + 1);
            thread.start();
            Log.info("live analysis on");
        } else if (!want && running) {
            running = false;
            thread = null;
            mask = null;
            rgb = null;
            Log.info("live analysis off");
            host.maskReady();
        }
    }

    public synchronized void stop() {
        running = false;
        thread = null;
        mask = null;
        rgb = null;
    }

    private void loop() {
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = 2;
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        int[] px = null;
        int[] out = null;
        int count = 0;
        Thread me = Thread.currentThread();
        while (running && thread == me) {
            long t0 = System.currentTimeMillis();
            if (host.zebraLevel() == 0 && host.peakingLevel() == 0 && !host.rgbHistogram()) {
                // Nothing left to do, e.g. the camera started delivering colour histograms itself
                synchronized (this) {
                    if (thread == me) {
                        running = false;
                        thread = null;
                    }
                }
                break;
            }
            if (!host.liveVisible() || session.isCapturing()) {
                if (mask != null) {
                    mask = null;
                    rgb = null;
                    host.maskReady();
                }
                sleep(300);
                continue;
            }
            byte[] jpeg = session.liveFrame();
            if (jpeg == null) {
                sleep(200);
                continue;
            }
            Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opt);
            if (b == null) {
                sleep(200);
                continue;
            }
            int w = b.getWidth(), h = b.getHeight();
            if (px == null || px.length != w * h) {
                px = new int[w * h];
                out = new int[w * h];
            }
            b.getPixels(px, 0, w, 0, 0, w, h);
            b.recycle();
            rgb = host.rgbHistogram() ? histogram(px) : null;
            int zebra = host.zebraLevel(), peak = host.peakingLevel();
            Bitmap m = null;
            if (zebra > 0 || peak > 0) {
                analyse(px, out, w, h, zebra, peak, host.peakingColor());
                m = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
            }
            if (!running || thread != me) {
                break;
            }
            // The previous mask is left to the GC: the overlay may still be drawing it
            mask = m;
            host.maskReady();
            lastMs = System.currentTimeMillis() - t0;
            if (count++ == 0) {
                Log.info("live analysis " + w + "x" + h + " in " + lastMs + " ms");
            }
            long rest = 200 - lastMs;
            if (rest > 0) {
                sleep(rest);
            }
        }
        if (thread == null) {
            mask = null;
        }
        host.maskReady();
    }

    /**
     * Zebra: diagonal stripes where the luma is above the level. Peaking: pixels whose
     * horizontal plus vertical difference of the luma exceeds the threshold.
     */
    static void analyse(int[] px, int[] out, int w, int h, int zebra, int peak, int peakColor) {
        int stripe = 0x00000000;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int i = row + x;
                int c = px[i];
                int l = luma(c);
                int o = stripe;
                if (zebra > 0 && l >= zebra && ((x + y) & 6) < 3) {
                    o = 0xC0FFFFFF;
                }
                if (peak > 0 && x > 0 && y > 0 && x < w - 1 && y < h - 1) {
                    int gx = luma(px[i + 1]) - luma(px[i - 1]);
                    int gy = luma(px[i + w]) - luma(px[i - w]);
                    if (Math.abs(gx) + Math.abs(gy) > peak) {
                        o = peakColor;
                    }
                }
                out[i] = o;
            }
        }
    }

    /** 128 bins per channel, like the camera's own luma histogram */
    static short[][] histogram(int[] px) {
        int[] r = new int[128], g = new int[128], b = new int[128];
        for (int c : px) {
            r[(c >> 17) & 0x7F]++;
            g[(c >> 9) & 0x7F]++;
            b[(c >> 1) & 0x7F]++;
        }
        // Scale into the short range the overlay expects
        int max = 1;
        for (int i = 0; i < 128; i++) {
            max = Math.max(max, Math.max(r[i], Math.max(g[i], b[i])));
        }
        short[][] out = new short[3][128];
        for (int i = 0; i < 128; i++) {
            out[0][i] = (short) (r[i] * 30000L / max);
            out[1][i] = (short) (g[i] * 30000L / max);
            out[2][i] = (short) (b[i] * 30000L / max);
        }
        return out;
    }

    private static int luma(int c) {
        return (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
