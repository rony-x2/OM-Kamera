package com.github.ronyx2.omkamera;

import android.hardware.Camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** All settings the app knows. New settings only need an entry here. */
public final class Settings {
    private static final Map<String, Setting> ALL = new LinkedHashMap<String, Setting>();

    static {
        // ================= Aufnahme =================
        add(new QualitySetting());

        add(new Setting("size", "Bildgröße", "Größe", Setting.Category.SHOOTING) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedImageSizes());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getImageSize();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setImageSize(v);
            }

            @Override
            public String display(String v) {
                return sizeLetter(v);
            }
        });

        add(new Setting("aspect", "Seitenverhältnis", "Format", Setting.Category.SHOOTING) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedImageAspectRatios());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getImageAspectRatio();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setImageAspectRatio(v);
            }
        });

        add(new DriveSetting());

        add(new Setting("bracketstep", "Reihe: Belichtungsstufe", "Reihe EV", Setting.Category.SHOOTING) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedExposureBracketPeriods());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return String.valueOf(p.mod.getExposureBracketPeriod());
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setExposureBracketPeriod(Integer.parseInt(v));
            }

            @Override
            protected boolean relevant(CameraSession.Params p) {
                return isExposureBracket(p);
            }

            @Override
            public String display(String v) {
                // The camera counts in tenths of an EV step
                return String.format(Locale.US, "%.1f EV", Integer.parseInt(v) / 10f).replace('.', ',');
            }
        });

        add(new Setting("bracketcount", "Reihe: Anzahl Bilder", "Reihe Anz.", Setting.Category.SHOOTING) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedNumsOfBracketPicture());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return String.valueOf(p.mod.getNumOfBracketPicture());
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setNumOfBracketPicture(Integer.parseInt(v));
            }

            @Override
            protected boolean relevant(CameraSession.Params p) {
                return isExposureBracket(p);
            }

            @Override
            public String display(String v) {
                return v + " Bilder";
            }
        });

        add(new Setting("bracketlevel", "Reihe WB/DRO: Stufe", "Reihe Stufe", Setting.Category.SHOOTING) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedBracketStepPeriods());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getBracketStepPeriod();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setBracketStepPeriod(v);
            }

            @Override
            protected boolean relevant(CameraSession.Params p) {
                return "bracket".equals(p.mod.getDriveMode()) && !"exposure".equals(p.mod.getBracketMode());
            }

            @Override
            public String display(String v) {
                return "high".equals(v) ? "Hi" : "low".equals(v) ? "Lo" : Labels.of(v);
            }
        });

        add(new Setting("bracketorder", "Reihe: Reihenfolge", "Reihenf.", Setting.Category.SHOOTING) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedBracketOrders());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getBracketOrder();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setBracketOrder(v);
            }

            @Override
            protected boolean relevant(CameraSession.Params p) {
                return "bracket".equals(p.mod.getDriveMode());
            }

            @Override
            public String display(String v) {
                return "start-zero".equals(v) ? "0 → − → +" : "start-minus".equals(v) ? "− → 0 → +" : v;
            }
        });

        add(new Setting("lenr", "Rauschmind. Langzeitbel.", "RM Lang", Setting.Category.SHOOTING) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return p.mod.isSupportedLongExposureNR() ? ON_OFF : null;
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getLongExposureNR() ? "on" : "off";
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setLongExposureNR("on".equals(v));
            }
        });

        // ================= Belichtung =================
        add(new Setting("iso", "ISO-Empfindlichkeit", "ISO", Setting.Category.EXPOSURE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedISOSensitivities());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return String.valueOf(p.mod.getISOSensitivity());
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setISOSensitivity(Integer.parseInt(v));
            }

            @Override
            public String display(String v) {
                return "0".equals(v) ? "AUTO" : v;
            }
        });

        add(new Setting("ev", "Belichtungskorrektur", "EV", Setting.Category.EXPOSURE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return range(p.params.getMinExposureCompensation(), p.params.getMaxExposureCompensation(), 1);
            }

            @Override
            protected String get(CameraSession.Params p) {
                return String.valueOf(p.params.getExposureCompensation());
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.params.setExposureCompensation(Integer.parseInt(v));
            }

            @Override
            public String display(CameraSession.Params p, String v) {
                // The camera works with an index, the step converts it to EV
                return ev(Integer.parseInt(v) * stepOf(p));
            }
        });

        add(new Setting("metering", "Messmethode", "Messung", Setting.Category.EXPOSURE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedMeteringModes());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getMeteringMode();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setMeteringMode(v);
            }
        });

        add(new DroHdrSetting());

        // ================= Bild =================
        add(new Setting("wb", "Weißabgleich", "WB", Setting.Category.IMAGE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return p.params.getSupportedWhiteBalance();
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.params.getWhiteBalance();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.params.setWhiteBalance(v);
            }

            @Override
            public String display(String v) {
                return "auto".equals(v) ? "AWB" : Labels.of(v);
            }
        });

        add(new Setting("wbtemp", "Farbtemperatur", "Kelvin", Setting.Category.IMAGE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return range(p.mod.getMinColorTemperatureForWhiteBalance(), p.mod.getMaxColorTemperatureForWhiteBalance(), 100);
            }

            @Override
            protected String get(CameraSession.Params p) {
                return String.valueOf(p.mod.getColorTemperatureForWhiteBalance());
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setColorTemperatureForWhiteBalance(Integer.parseInt(v));
            }

            @Override
            protected boolean relevant(CameraSession.Params p) {
                String wb = p.params.getWhiteBalance();
                return wb != null && wb.contains("temp");
            }

            @Override
            public String display(String v) {
                return v + " K";
            }
        });

        add(new Setting("wbab", "WB-Feinkorrektur A–B", "WB A–B", Setting.Category.IMAGE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return range(p.mod.getMinLightBalanceForWhiteBalance(), p.mod.getMaxLightBalanceForWhiteBalance(), 1);
            }

            @Override
            protected String get(CameraSession.Params p) {
                return String.valueOf(p.mod.getLightBalanceForWhiteBalance());
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setLightBalanceForWhiteBalance(Integer.parseInt(v));
            }

            @Override
            public String display(String v) {
                // Positive values shift towards amber, negative towards blue
                int i = Integer.parseInt(v);
                return i > 0 ? "A" + i : i < 0 ? "B" + (-i) : "0";
            }
        });

        add(new Setting("wbgm", "WB-Feinkorrektur G–M", "WB G–M", Setting.Category.IMAGE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return range(p.mod.getMinColorCompensationForWhiteBalance(), p.mod.getMaxColorCompensationForWhiteBalance(), 1);
            }

            @Override
            protected String get(CameraSession.Params p) {
                return String.valueOf(p.mod.getColorCompensationForWhiteBalance());
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setColorCompensationForWhiteBalance(Integer.parseInt(v));
            }

            @Override
            public String display(String v) {
                // Positive values shift towards magenta, negative towards green
                int i = Integer.parseInt(v);
                return i > 0 ? "M" + i : i < 0 ? "G" + (-i) : "0";
            }
        });

        add(new LookSetting());

        add(new Setting("style", "Kreativmodus", "Stil", Setting.Category.IMAGE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedColorModes());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getColorMode();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setColorMode(v);
            }
        });

        // Like the camera's menu, the creative style parameters are limited to -3..+3
        add(new Setting("contrast", "Stil: Kontrast", "Kontrast", Setting.Category.IMAGE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return range(Math.max(-3, p.mod.getMinContrast()), Math.min(3, p.mod.getMaxContrast()), 1);
            }

            @Override
            protected String get(CameraSession.Params p) {
                return String.valueOf(p.mod.getContrast());
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setContrast(Integer.parseInt(v));
            }

            @Override
            public String display(String v) {
                return signed(v);
            }
        });

        add(new Setting("saturation", "Stil: Sättigung", "Sättigung", Setting.Category.IMAGE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return range(Math.max(-3, p.mod.getMinSaturation()), Math.min(3, p.mod.getMaxSaturation()), 1);
            }

            @Override
            protected String get(CameraSession.Params p) {
                return String.valueOf(p.mod.getSaturation());
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setSaturation(Integer.parseInt(v));
            }

            @Override
            protected boolean relevant(CameraSession.Params p) {
                // Black and white styles have no saturation
                String style = p.mod.getColorMode();
                return !"mono".equals(style) && !"sepia".equals(style);
            }

            @Override
            public String display(String v) {
                return signed(v);
            }
        });

        add(new Setting("sharpness", "Stil: Schärfe", "Schärfe", Setting.Category.IMAGE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return range(Math.max(-3, p.mod.getMinSharpness()), Math.min(3, p.mod.getMaxSharpness()), 1);
            }

            @Override
            protected String get(CameraSession.Params p) {
                return String.valueOf(p.mod.getSharpness());
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setSharpness(Integer.parseInt(v));
            }

            @Override
            public String display(String v) {
                return signed(v);
            }
        });

        add(new Setting("effect", "Bildeffekt", "Effekt", Setting.Category.IMAGE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedPictureEffects());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getPictureEffect();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setPictureEffect(v);
            }

            @Override
            protected boolean relevant(CameraSession.Params p) {
                // Like on the camera: picture effects only work with JPEG
                return !isRaw(p);
            }
        });

        add(new Setting("softskin", "Soft Skin-Effekt", "Soft Skin", Setting.Category.IMAGE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedSoftSkinEffects());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getSoftSkinEffect();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setSoftSkinEffect(v);
            }
        });

        add(new Setting("colorspace", "Farbraum", "Farbraum", Setting.Category.IMAGE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedColorSpaces());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getColorSpace();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setColorSpace(v);
            }

            @Override
            public String display(String v) {
                return "standard".equals(v) ? "sRGB" : "expanded".equals(v) ? "AdobeRGB" : v;
            }
        });

        // ================= Fokus =================
        add(new Setting("afmf", "Fokusmodus AF/MF", "AF/MF", Setting.Category.FOCUS) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return p.params.getSupportedFocusModes();
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.params.getFocusMode();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.params.setFocusMode(v);
            }

            @Override
            public String display(String v) {
                return Camera.Parameters.FOCUS_MODE_AUTO.equals(v) ? "AF" : Labels.of(v);
            }
        });

        add(new Setting("afmode", "AF-Modus", "AF-Modus", Setting.Category.FOCUS) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedAutoFocusModes());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getAutoFocusMode();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setAutoFocusMode(v);
            }

            @Override
            protected boolean relevant(CameraSession.Params p) {
                return !"manual".equals(p.params.getFocusMode());
            }
        });

        add(new Setting("afarea", "Fokusfeld", "AF-Feld", Setting.Category.FOCUS) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedFocusAreaModes());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getFocusAreaMode();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setFocusAreaMode(v);
            }

            @Override
            protected boolean relevant(CameraSession.Params p) {
                return !"manual".equals(p.params.getFocusMode());
            }
        });

        add(new Setting("spotsize", "Spot-Größe", "Spot", Setting.Category.FOCUS) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedFocusAreaFlexibleSpotSizes());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getFocusAreaFlexibleSpotSize();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setFocusAreaFlexibleSpotSize(v);
            }

            @Override
            protected boolean relevant(CameraSession.Params p) {
                return isFlexSpot(p);
            }

            @Override
            public String display(String v) {
                return sizeLetter(v);
            }
        });

        add(new Setting("face", "Gesichtserkennung", "Gesicht", Setting.Category.FOCUS) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedFaceDetectionModes());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getFaceDetectionMode();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setFaceDetectionMode(v);
            }

            @Override
            public String display(String v) {
                return "auto".equals(v) ? "Ein" : Labels.of(v);
            }
        });

        // ================= Blitz =================
        add(new FlashSetting());

        add(new Setting("flashcomp", "Blitzkorrektur", "Blitz ±", Setting.Category.FLASH) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return range(p.mod.getMinFlashCompensation(), p.mod.getMaxFlashCompensation(), 1);
            }

            @Override
            protected String get(CameraSession.Params p) {
                return flashReady ? String.valueOf(p.mod.getFlashCompensation()) : null;
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setFlashCompensation(Integer.parseInt(v));
            }

            @Override
            public String display(CameraSession.Params p, String v) {
                float step = 1f / 3f;
                try {
                    float st = p.mod.getFlashCompensationStep();
                    if (st > 0) {
                        step = st;
                    }
                } catch (Throwable ignored) {
                }
                return ev(Integer.parseInt(v) * step);
            }
        });

        add(new Setting("redeye", "Rote-Augen-Reduzierung", "Rote Augen", Setting.Category.FLASH) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedRedEyeReductionModes());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getRedEyeReductionMode();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setRedEyeReductionMode(v);
            }
        });
    }

    private static final List<String> ON_OFF = java.util.Arrays.asList("off", "on");

    /** RAW or RAW+JPEG */
    static boolean isRaw(CameraSession.Params p) {
        try {
            String q = p.mod.getPictureStorageFormat();
            return q != null && q.startsWith("raw");
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isExposureBracket(CameraSession.Params p) {
        return "bracket".equals(p.mod.getDriveMode()) && "exposure".equals(p.mod.getBracketMode());
    }

    static boolean isFlexSpot(CameraSession.Params p) {
        String a = p.mod.getFocusAreaMode();
        return a != null && (a.contains("flex") || a.equals("local"));
    }

    /** small / midium / large as S, M, L like on the camera */
    static String sizeLetter(String v) {
        if ("large".equals(v)) {
            return "L";
        }
        if ("midium".equals(v) || "middle".equals(v) || "medium".equals(v)) {
            return "M";
        }
        if ("small".equals(v)) {
            return "S";
        }
        return Labels.of(v);
    }

    private static float stepOf(CameraSession.Params p) {
        if (p == null) {
            return 1f / 3f;
        }
        try {
            float s = p.params.getExposureCompensationStep();
            return s > 0 ? s : 1f / 3f;
        } catch (Throwable t) {
            return 1f / 3f;
        }
    }

    /** Set by the activity from the camera's equipment reports */
    static volatile boolean flashReady;

    /** Self-timer counted by the app (seconds, 0 = off) for times the camera does not offer */
    static volatile int appTimer;
    private static android.content.SharedPreferences prefs;

    static void init(android.content.SharedPreferences p) {
        prefs = p;
        appTimer = p.getInt("apptimer", 0);
    }

    /** Drive mode chosen in the app (the camera drops it when the app starts again) */
    static String savedDrive() {
        return prefs != null ? prefs.getString("drive", null) : null;
    }

    static void rememberDrive(String v) {
        if (prefs != null && v != null) {
            prefs.edit().putString("drive", v).apply();
        }
    }

    static void setAppTimer(int seconds) {
        appTimer = seconds;
        if (prefs != null) {
            prefs.edit().putInt("apptimer", seconds).apply();
        }
    }

    static String ev(float ev) {
        if (Math.abs(ev) < 0.05f) {
            return "±0,0";
        }
        // The camera's locale data has no German number format, so the comma is set by hand
        return String.format(Locale.US, "%+.1f", ev).replace('.', ',');
    }

    private static void add(Setting s) {
        ALL.put(s.id, s);
    }

    private Settings() {
    }

    public static Setting get(String id) {
        return ALL.get(id);
    }

    public static List<Setting> all() {
        return Collections.unmodifiableList(new ArrayList<Setting>(ALL.values()));
    }

    public static List<Setting> of(Setting.Category category) {
        List<Setting> out = new ArrayList<Setting>();
        for (Setting s : ALL.values()) {
            if (s.category == category) {
                out.add(s);
            }
        }
        return out;
    }

    /** Default Fn grid: 2 rows with 6 cells, like the camera's own Fn menu. */
    public static final String[] DEFAULT_FN = {
        "drive", "afmf", "afmode", "afarea", "iso", "ev",
        "wb", "metering", "dro", "style", "flash", "quality"
    };
}
