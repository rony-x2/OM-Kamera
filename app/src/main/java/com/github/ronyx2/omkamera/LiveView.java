package com.github.ronyx2.omkamera;

import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.hardware.CameraSequence;
import com.sony.scalar.hardware.DeviceBuffer;
import com.sony.scalar.hardware.DeviceMemory;

import java.nio.ByteBuffer;

/**
 * JPEG frames of the live view for the browser. The camera encodes them itself through a
 * preview sequence (format 256 = JPEG), the same mechanism the camera's remote control uses.
 * The sequence runs only while frames are requested and stops after a few idle seconds.
 */
public class LiveView {
    private static final int MAX_KB = 100;
    private static final long IDLE_STOP_MS = 5000;

    private CameraSequence sequence;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(MAX_KB * 1024);
    private long lastRequest;

    public synchronized byte[] frame(CameraEx cameraEx) {
        lastRequest = System.currentTimeMillis();
        if (cameraEx == null) {
            return null;
        }
        try {
            if (sequence == null) {
                sequence = CameraSequence.open(cameraEx);
                CameraSequence.Options opts = new CameraSequence.Options();
                opts.setOption("PREVIEW_FRAME_RATE", 30000);
                opts.setOption("PREVIEW_FRAME_WIDTH", 640);
                opts.setOption("PREVIEW_FRAME_HEIGHT", 0);
                opts.setOption("PREVIEW_FRAME_FORMAT", 256);
                opts.setOption("PREVIEW_FRAME_MAX_NUM", 1);
                opts.setOption("JPEG_COMPRESS_RATE_DENOM", 15);
                opts.setOption("JPEG_COMPRESS_MAX_SIZE", MAX_KB);
                sequence.startPreviewSequence(opts);
                Log.info("live view sequence started");
            }
            DeviceMemory[] frames = sequence.getPreviewSequenceFrames(1);
            if (frames == null || frames.length == 0) {
                return null;
            }
            try {
                DeviceBuffer b = (DeviceBuffer) frames[0];
                int size = Math.min(b.getSize(), buffer.capacity());
                buffer.rewind();
                b.read(buffer, size, 0);
                byte[] out = new byte[size];
                buffer.rewind();
                buffer.get(out, 0, size);
                return out;
            } finally {
                for (DeviceMemory m : frames) {
                    m.release();
                }
            }
        } catch (Throwable t) {
            Log.error("live view frame", t);
            stop();
            return null;
        }
    }

    public synchronized String splitExposure() {
        return HardwareProbe.splitExposure(sequence);
    }

    /** Stops the sequence when no frame was requested for a while. */
    public synchronized void stopIfIdle() {
        if (sequence != null && System.currentTimeMillis() - lastRequest > IDLE_STOP_MS) {
            stop();
        }
    }

    public synchronized void stop() {
        if (sequence == null) {
            return;
        }
        try {
            sequence.stopPreviewSequence();
        } catch (Throwable t) {
            Log.error("stopPreviewSequence", t);
        }
        try {
            sequence.release();
        } catch (Throwable t) {
            Log.error("sequence release", t);
        }
        sequence = null;
        Log.info("live view sequence stopped");
    }
}
