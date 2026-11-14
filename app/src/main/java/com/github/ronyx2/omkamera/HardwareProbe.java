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
        out.put("cpu.abi", android.os.Build.CPU_ABI + (android.os.Build.CPU_ABI2 != null ? ", " + android.os.Build.CPU_ABI2 : ""));
        out.put("cpu.info", cpuInfo());
        out.put("BitmapRegionDecoder", has("android.graphics.BitmapRegionDecoder"));
        out.put("JpegExporter", has("com.sony.scalar.graphics.JpegExporter"));
        out.put("OptimizedImageFactory", has("com.sony.scalar.graphics.OptimizedImageFactory"));

        out.put("rawimage.mp", prop("mem.rawimage.size.in.mega.pixel"));
        out.put("device.memory", prop("device.memory"));
        out.put("etc.memmap", list("/etc/memmap", null));
        // Sony apps bring their own memory maps in their lib folder
        StringBuilder apps = new StringBuilder();
        java.io.File[] data = new java.io.File("/data/data").listFiles();
        if (data != null) {
            for (java.io.File d : data) {
                String l = list(d.getPath() + "/lib", "libILDCSC");
                if (l.length() > 0 && !l.startsWith("(")) {
                    apps.append(d.getName()).append(": ").append(l).append("; ");
                }
            }
        } else {
            apps.append("(/data/data nicht lesbar)");
        }
        out.put("app.memmaps", apps.length() > 0 ? apps.toString() : "keine");
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

    private static String prop(String key) {
        try {
            return String.valueOf(com.sony.scalar.sysutil.ScalarProperties.getString(key));
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    private static String list(String dir, String prefix) {
        java.io.File[] fs = new java.io.File(dir).listFiles();
        if (fs == null) {
            return "(nicht lesbar)";
        }
        StringBuilder b = new StringBuilder();
        for (java.io.File f : fs) {
            if (prefix == null || f.getName().startsWith(prefix)) {
                b.append(f.getName()).append(' ');
            }
        }
        return b.toString().trim();
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

    /** Processor, architecture and features from /proc/cpuinfo (read only) */
    private static String cpuInfo() {
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/cpuinfo"));
            String l;
            while ((l = r.readLine()) != null) {
                String k = l.split(":")[0].trim();
                if (k.equals("Processor") || k.equals("model name") || k.equals("CPU architecture")
                        || k.equals("Features") || k.equals("CPU part") || k.equals("BogoMIPS") || k.equals("Hardware")) {
                    String v = l.substring(l.indexOf(':') + 1).trim();
                    if (sb.indexOf(k + "=" + v) < 0) {
                        sb.append(sb.length() > 0 ? "; " : "").append(k).append('=').append(v);
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("nicht lesbar");
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (java.io.IOException ignored) {
                }
            }
        }
        return sb.toString();
    }
}
