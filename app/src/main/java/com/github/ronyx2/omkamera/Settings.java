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
        // ---- Belichtung ----
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
                List<String> out = new ArrayList<String>();
                for (int i = p.params.getMinExposureCompensation(); i <= p.params.getMaxExposureCompensation(); i++) {
                    out.add(String.valueOf(i));
                }
                return out;
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
                float step = stepOf(p);
                return ev(Integer.parseInt(v) * step);
            }
        });

        add(new Setting("wb", "Weißabgleich", "WB", Setting.Category.EXPOSURE) {
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

        add(new Setting("dro", "DRO / Auto-HDR", "DRO", Setting.Category.EXPOSURE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedDROModes());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getDROMode();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setDROMode(v);
            }
        });

        add(new Setting("style", "Kreativmodus", "Stil", Setting.Category.EXPOSURE) {
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

        add(new Setting("flash", "Blitzmodus", "Blitz", Setting.Category.EXPOSURE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return p.params.getSupportedFlashModes();
            }

            @Override
            protected String get(CameraSession.Params p) {
                // Only offered while the built-in flash is up or an external flash is attached
                return flashReady ? p.params.getFlashMode() : null;
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.params.setFlashMode(v);
            }
        });

        add(new Setting("flashcomp", "Blitzkorrektur", "Blitz ±", Setting.Category.EXPOSURE) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                List<String> out = new ArrayList<String>();
                for (int i = p.mod.getMinFlashCompensation(); i <= p.mod.getMaxFlashCompensation(); i++) {
                    out.add(String.valueOf(i));
                }
                return out;
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

        // ---- Fokus ----
        add(new Setting("afmf", "Fokus AF/MF", "AF/MF", Setting.Category.FOCUS) {
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
        });

        // ---- Aufnahme ----
        add(new Setting("drive", "Bildfolgemodus", "Bildfolge", Setting.Category.SHOOTING) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedDriveModes());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getDriveMode();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setDriveMode(v);
            }
        });

        add(new Setting("quality", "Qualität", "Qualität", Setting.Category.SHOOTING) {
            @Override
            protected List<String> values(CameraSession.Params p) {
                return strings(p.mod.getSupportedPictureStorageFormats());
            }

            @Override
            protected String get(CameraSession.Params p) {
                return p.mod.getPictureStorageFormat();
            }

            @Override
            protected void set(CameraSession.Params e, String v) {
                e.mod.setPictureStorageFormat(v);
            }
        });

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

    static String ev(float ev) {
        if (Math.abs(ev) < 0.05f) {
            return "±0,0";
        }
        return String.format(Locale.GERMANY, "%+.1f", ev);
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
