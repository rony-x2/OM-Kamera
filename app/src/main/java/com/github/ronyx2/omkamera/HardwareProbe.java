package com.github.ronyx2.omkamera;

import com.sony.scalar.graphics.imagefilter.ImageFilter;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.hardware.CameraSequence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Asks the camera which image processing it offers, without taking a picture: the RAW develop
 * filters (multi frame addition, HDR), split exposures and the general image filters. Each
 * filter is only created, asked {@code isSupported()} and released again.
 */
public final class HardwareProbe {

    private HardwareProbe() {
    }

    public static Map<String, String> run(CameraEx cameraEx) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Runtime rt = Runtime.getRuntime();
        out.put("heap.max", (rt.maxMemory() / (1024 * 1024)) + " MB");
        out.put("BitmapRegionDecoder", has("android.graphics.BitmapRegionDecoder"));
        out.put("JpegExporter", has("com.sony.scalar.graphics.JpegExporter"));
        out.put("OptimizedImageFactory", has("com.sony.scalar.graphics.OptimizedImageFactory"));

        filter(out, "RawRawAdditionNR", "com.sony.scalar.hardware.CameraSequence$RawRawAdditionNRDevelopFilter");
        filter(out, "RawImgAdditionNR", "com.sony.scalar.hardware.CameraSequence$RawImgAdditionNRDevelopFilter");
        filter(out, "HDRDevelop", "com.sony.scalar.hardware.CameraSequence$HDRDevelopFilter");
        filter(out, "DefaultDevelop", "com.sony.scalar.hardware.CameraSequence$DefaultDevelopFilter");
        filter(out, "SimpleDevelop", "com.sony.scalar.hardware.CameraSequence$SimpleDevelopFilter");
        filter(out, "RichtoneMonochrome", "com.sony.scalar.hardware.CameraSequence$RichtoneMonochromeDevelopFilter");
        filter(out, "ArtEffectDevelop", "com.sony.scalar.hardware.CameraSequence$ArtEffectDevelopFilter");
        filter(out, "Scale", "com.sony.scalar.graphics.imagefilter.ScaleImageFilter");
        filter(out, "Crop", "com.sony.scalar.graphics.imagefilter.CropImageFilter");
        filter(out, "SuperResolution", "com.sony.scalar.graphics.imagefilter.SuperResolutionImageFilter");
        filter(out, "SoftFocus", "com.sony.scalar.graphics.imagefilter.SoftFocusImageFilter");
        filter(out, "ContrastPlus", "com.sony.scalar.graphics.imagefilter.ContrastPlusImageFilter");
        return out;
    }

    /** Split exposure needs a sequence; asked through the live view's own sequence */
    public static String splitExposure(CameraSequence seq) {
        if (seq == null) {
            return "keine Sequenz";
        }
        try {
            return seq.isSplitExposureSupported() ? "ja" : "nein";
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    private static String has(String cls) {
        try {
            Class.forName(cls);
            return "ja";
        } catch (Throwable t) {
            return "nein";
        }
    }

    private static void filter(Map<String, String> out, String name, String cls) {
        ImageFilter f = null;
        try {
            f = (ImageFilter) Class.forName(cls).newInstance();
            out.put(name, f.isSupported() ? "ja" : "nein");
        } catch (Throwable t) {
            out.put(name, t.getClass().getSimpleName());
        } finally {
            if (f != null) {
                try {
                    f.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
