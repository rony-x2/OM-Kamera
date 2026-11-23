# OM Kamera

An alternative camera app for Sony cameras with the PlayMemories Camera Apps platform,
built on [OpenMemories](https://github.com/ma1co/OpenMemories-Framework).
Developed and tested on the **ILCE-6000 (a6000)**; ILCE-7M2 and ILCE-7RM2 are supported by the code
but less tested.

The display is available in **English and German** (Setup → Sprache / Language).

> Status: personal project, works well on the author's cameras. Use at your own risk –
> the app talks to undocumented camera interfaces.

## Screenshots

See [`docs/screenshots`](docs/screenshots).

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
