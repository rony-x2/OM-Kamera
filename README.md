# OM Kamera

A camera app for Sony cameras with the PlayMemories Camera Apps platform (e.g. ILCE-6000, ILCE-7M2, ILCE-7RM2).
Working title, early prototype.

## Features (prototype)
* Live view with exposure bar (shutter speed, aperture, exposure compensation, ISO)
* Fn grid with 2 × 6 cells, assignment stored as data and editable in the menu
* Menu with tabs (shooting, exposure, focus, Fn grid, system)
* Settings layer: one adapter per setting (allowed values, current value, write);
  refusals by the camera are shown with the camera's reason code
* Dials: upper/front dial shutter speed, control wheel and rear dial aperture, third dial exposure compensation
* Log file `OMKAMLOG.TXT` on the memory card

## Building
Requires the Android SDK. The Sony API stubs and the framework wrappers from
[OpenMemories-Framework](https://github.com/ma1co/OpenMemories-Framework) are in `app/libs`.

    ./gradlew assembleDebug

Install with [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE): `pmca-console install -f app-debug.apk`
