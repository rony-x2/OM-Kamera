package com.github.ronyx2.omkamera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Writes a Motion-JPEG AVI from JPEG frames without re-encoding them. Used for the time-lapse
 * film of the interval recording; the frames come from the live view (640 px wide), so the file
 * stays small and every player can show it.
 *
 * Layout: RIFF AVI ( hdrl ( avih, strl ( strh, strf ) ), movi ( 00dc ... ), idx1 ).
 * Sizes and frame counts are written as placeholders and patched in {@link #close()}.
 */
public class MjpegAvi {
    private final RandomAccessFile f;
    private final int fps;
    private int width;
    private int height;
    private int frames;
    private int maxFrame;
    private long moviStart;
    private final ByteArrayOutputStream index = new ByteArrayOutputStream();

    // Offsets of the fields that are known only at the end
    private static final int OFF_RIFF_SIZE = 4;
    private static final int OFF_AVIH = 24;

    public MjpegAvi(File file, int fps) throws IOException {
        this.fps = fps;
        file.getParentFile().mkdirs();
        // The camera's card file system cannot truncate files: always start with a new file
        if (file.exists() && !file.delete()) {
            throw new IOException("Datei " + file.getName() + " lässt sich nicht ersetzen");
        }
        f = new RandomAccessFile(file, "rw");
    }

    public int frames() {
        return frames;
    }

    /** Adds one JPEG; the first one decides the picture size */
    public void add(byte[] jpeg) throws IOException {
        if (jpeg == null || jpeg.length < 4) {
            return;
        }
        if (frames == 0) {
            int[] wh = jpegSize(jpeg);
            width = wh[0];
            height = wh[1];
            writeHeader();
        }
        long pos = f.getFilePointer();
        writeFourCC("00dc");
        writeInt(jpeg.length);
        f.write(jpeg);
        if ((jpeg.length & 1) != 0) {
            f.write(0);
        }
        // Index entry: offset relative to the "movi" fourcc
        idx("00dc");
        idxInt(0x10);
        idxInt((int) (pos - moviStart));
        idxInt(jpeg.length);
        frames++;
        maxFrame = Math.max(maxFrame, jpeg.length);
    }

    public void close() throws IOException {
        try {
            if (frames == 0) {
                return;
            }
            long moviEnd = f.getFilePointer();
            writeFourCC("idx1");
            byte[] idx = index.toByteArray();
            writeInt(idx.length);
            f.write(idx);
            long end = f.getFilePointer();
            // RIFF size
            f.seek(OFF_RIFF_SIZE);
            writeInt((int) (end - 8));
            // movi LIST size
            f.seek(moviStart - 4);
            writeInt((int) (moviEnd - moviStart));
            // avih: total frames, max bytes/s, suggested buffer
            f.seek(OFF_AVIH + 8 + 16);
            writeInt(frames);
            f.seek(OFF_AVIH + 8 + 4);
            writeInt(maxFrame * fps);
            f.seek(OFF_AVIH + 8 + 28);
            writeInt(maxFrame);
            // strh length and buffer size
            f.seek(strhData + 32);
            writeInt(frames);
            writeInt(maxFrame);
        } finally {
            f.close();
        }
    }

    private long strhData;

    private void writeHeader() throws IOException {
        writeFourCC("RIFF");
        writeInt(0);
        writeFourCC("AVI ");
        // hdrl list: avih (8 + 56) + strl list (12 + strh 8+56 + strf 8+40)
        int strlSize = 4 + (8 + 56) + (8 + 40);
        int hdrlSize = 4 + (8 + 56) + (8 + strlSize);
        writeFourCC("LIST");
        writeInt(hdrlSize);
        writeFourCC("hdrl");
        // avih at offset 32
        writeFourCC("avih");
        writeInt(56);
        writeInt(1000000 / fps);   // microseconds per frame
        writeInt(0);               // max bytes per second (patched)
        writeInt(0);               // padding granularity
        writeInt(0x10);            // flags: AVIF_HASINDEX
        writeInt(0);               // total frames (patched)
        writeInt(0);               // initial frames
        writeInt(1);               // streams
        writeInt(0);               // suggested buffer size (patched)
        writeInt(width);
        writeInt(height);
        writeInt(0);
        writeInt(0);
        writeInt(0);
        writeInt(0);
        // strl
        writeFourCC("LIST");
        writeInt(strlSize);
        writeFourCC("strl");
        writeFourCC("strh");
        writeInt(56);
        strhData = f.getFilePointer();
        writeFourCC("vids");
        writeFourCC("MJPG");
        writeInt(0);               // flags
        writeInt(0);               // priority, language
        writeInt(0);               // initial frames
        writeInt(1);               // scale
        writeInt(fps);             // rate
        writeInt(0);               // start
        writeInt(0);               // length (patched)
        writeInt(0);               // suggested buffer (patched)
        writeInt(-1);              // quality
        writeInt(0);               // sample size
        writeShort(0);
        writeShort(0);
        writeShort(width);
        writeShort(height);
        writeFourCC("strf");
        writeInt(40);
        writeInt(40);              // BITMAPINFOHEADER size
        writeInt(width);
        writeInt(height);
        writeShort(1);             // planes
        writeShort(24);            // bit count
        writeFourCC("MJPG");       // compression
        writeInt(width * height * 3);
        writeInt(0);
        writeInt(0);
        writeInt(0);
        writeInt(0);
        // movi list
        writeFourCC("LIST");
        writeInt(0);               // patched
        moviStart = f.getFilePointer();
        writeFourCC("movi");
    }

    /** Width and height from the SOF marker of a JPEG; 640x427 if not found */
    static int[] jpegSize(byte[] j) {
        int i = 2;
        while (i + 9 < j.length) {
            if ((j[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = j[i + 1] & 0xFF;
            int len = ((j[i + 2] & 0xFF) << 8) | (j[i + 3] & 0xFF);
            if (marker >= 0xC0 && marker <= 0xC3) {
                int h = ((j[i + 5] & 0xFF) << 8) | (j[i + 6] & 0xFF);
                int w = ((j[i + 7] & 0xFF) << 8) | (j[i + 8] & 0xFF);
                return new int[]{w, h};
            }
            i += 2 + len;
        }
        return new int[]{640, 427};
    }

    private void writeFourCC(String s) throws IOException {
        f.write(s.getBytes("US-ASCII"));
    }

    private void writeInt(int v) throws IOException {
        f.write(v & 0xFF);
        f.write((v >> 8) & 0xFF);
        f.write((v >> 16) & 0xFF);
        f.write((v >> 24) & 0xFF);
    }

    private void writeShort(int v) throws IOException {
        f.write(v & 0xFF);
        f.write((v >> 8) & 0xFF);
    }

    private void idx(String s) {
        byte[] b = s.getBytes();
        index.write(b, 0, b.length);
    }

    private void idxInt(int v) {
        index.write(v & 0xFF);
        index.write((v >> 8) & 0xFF);
        index.write((v >> 16) & 0xFF);
        index.write((v >> 24) & 0xFF);
    }
}
