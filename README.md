# OM Kamera

An alternative camera app for Sony cameras with the PlayMemories Camera Apps platform,
built on [OpenMemories](https://github.com/ma1co/OpenMemories-Framework).
Developed and tested on the **ILCE-6000 (a6000)**; ILCE-7M2 and ILCE-7RM2 are supported by the code
but less tested.

The display is available in **English and German** (Setup → Sprache / Language).

> Status: personal project, works well on the author's cameras. Use at your own risk –
> the app talks to undocumented camera interfaces.

## Screenshots

![Overview: live view, zebra and focus peaking, focus magnifier, Holy Grail time-lapse](docs/screenshots/00-overview.jpg)

| Zebra and focus peaking | Focus magnifier |
|---|---|
| ![Zebra and focus peaking](docs/screenshots/11-zebra-focus-peaking.png) | ![Focus magnifier](docs/screenshots/12-focus-magnifier.png) |

| Time-lapse (LRT) with BULB | Daily time window and weekdays |
|---|---|
| ![Time-lapse](docs/screenshots/13-time-lapse.png) | ![Time window](docs/screenshots/14-time-lapse-window.png) |

| Holy Grail exposure ramping | RAW processing on the camera |
|---|---|
| ![Holy Grail](docs/screenshots/15-holy-grail.png) | ![RAW processing](docs/screenshots/16-raw-processing.png) |

| Live view with status line and RGB histogram | Fn menu |
|---|---|
| ![Live view](docs/screenshots/01-live-view.png) | ![Fn menu](docs/screenshots/02-fn-menu.png) |

| Main | Shooting |
|---|---|
| ![Menu main](docs/screenshots/03-menu-main.png) | ![Menu shooting](docs/screenshots/04-menu-shooting.png) |

| Exposure/Color (auto ISO with minimum shutter speed) | Focus |
|---|---|
| ![Menu exposure](docs/screenshots/05-menu-exposure.png) | ![Menu focus](docs/screenshots/06-menu-focus.png) |

| Setup: clock (NTP correction) | Setup: operation, language, direct start |
|---|---|
| ![Menu setup clock](docs/screenshots/07-menu-setup-clock.png) | ![Menu setup operation](docs/screenshots/08-menu-setup-operation.png) |

**Remote control in the browser** – live view with the camera display, exposure, camera buttons and all settings:

![Remote control](docs/screenshots/09-remote-control.jpg)

<img src="docs/screenshots/10-remote-settings.png" alt="Remote control settings panel" width="320">

## Features

**Shooting**
* Live view with exposure bar, histogram, grid lines (3×3, golden ratio, square, diagonal), level marker,
  zebra and focus peaking, warnings (camera shake, clipped highlights, battery, card)
* Fn menu with up to 18 freely assigned slots, menu with tabs and "My Menu", freely assignable custom buttons
* Auto ISO with a minimum shutter speed (by focal length or fixed)
* Long exposures beyond 30": own list of times (e.g. 40", 90", 5'), exposed with BULB and timed by the app;
  BULB timer

**Sequences**
* Interval shooting and time-lapse (LRT) with start delay, start at a time of day, daily time window and weekdays
* BULB time-lapse, "Holy Grail" exposure ramping for sunsets/sunrises, interval ramp
* Preview movie (MJPEG AVI) and a log file for each run
* Exposure bracketing+, focus bracketing (where the camera allows focus control)

**RAW processing on the camera**
* Combine the last N RAWs: average (less noise), sum, maximum (star trails, light trails), focus stacking
* Export as DNG, 16-bit TIFF, PNG; HEIC/AVIF (experimental)
* Native ARW decoder (multi-threaded C, ARM mode)

**Connectivity**
* Remote control in the browser (live view, settings, shutter) with an access code
* MCP interface for AI assistants (token protected), bridge in `tools/mcp-bridge`
* FTP upload of new pictures
* Clock correction via NTP (the a6000 cannot set its clock from apps; the app keeps the offset)
* Read-only system diagnostics with root rights (fixed queries, no free commands)

**Integration**
* Direct start: "Application" in the Sony menu opens OM Kamera directly (optional);
  open "Application" within 20 s after closing the app to get the normal app list

## Cameras

Sony offered PlayMemories Camera Apps on 28 models, from the NEX-5R (2012) to the a6500 and
RX100 V (2016); the app store closed on 31 August 2025. OM Kamera installs without it. The camera
interfaces differ between generations, so each model needs its own test:

| Status | Models |
|---|---|
| Tested | ILCE-6000 (a6000), firmware 3.21 |
| Supported by the code | ILCE-7M2 (a7II), ILCE-7RM2 (a7RII) |
| Interchangeable lens, not tested yet | ILCE-5000, ILCE-5100, ILCE-6300, ILCE-6500, ILCE-7, ILCE-7R, ILCE-7S, ILCE-7SM2, NEX-5R, NEX-5T, NEX-6 |
| Compact, not tested yet | DSC-RX100M3, RX100M4, RX100M5, RX10M2, RX10M3, RX1RM2, HX60(V), HX80, HX90(V), HX400(V), WX500 |

Tried it on another model? Please open an [issue](../../issues) – what works, what does not.

## Installing

Download the APK from the [releases](../../releases) and install it with
[Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE):

    pmca-console install -f OM-Kamera-<version>.apk

## Building

Requires the Android SDK (platform 10 is enough) and, for the native parts, NDK r14b.
The Sony API stubs and the framework wrappers from
[OpenMemories-Framework](https://github.com/ma1co/OpenMemories-Framework) are in `app/libs`.

    ./gradlew assembleDebug

The native libraries are prebuilt in `app/src/main/jniLibs/armeabi`; to rebuild them run
`ndk-build` in `native/` (see `native/THIRD_PARTY.md` for the HEIC/AVIF encoders).

## Translation

The app is written in German; English is applied when text is drawn, from
`app/src/main/assets/lang/en.tsv` (German text, tab, English text). Texts that stay German in the
English display are listed by the app at `/api/i18n` (remote control code required).

## License

MIT, see [LICENSE](LICENSE). Third-party parts keep their own licenses
(OpenMemories, Kvazaar, libaom).
