# Fremdbibliotheken der nativen Teile

`libomkheif.so` (HEIC/AVIF, Versuch) enthält zwei Encoder, die außerhalb des Repos
gebaut werden. Lizenztexte: `app/src/main/assets/third_party_licenses.txt`.

| Bibliothek | Version | Lizenz |
|---|---|---|
| Kvazaar (HEVC) | Commit `9cf31e73785fe796bd8d62a27315a82665eef8c7` | BSD 3-Clause |
| libaom (AV1) | Tag `v3.2.0` (letzte Version ohne C++17-Pflicht für GCC 4.9) | BSD 2-Clause + AOM-Patentlizenz |

## Bauen (NDK r14b, GCC 4.9, armeabi, android-12)

```sh
git clone https://github.com/ultravideo/kvazaar /home/claude/thirdparty/kvazaar
git -C /home/claude/thirdparty/kvazaar checkout 9cf31e73785fe796bd8d62a27315a82665eef8c7
git clone https://aomedia.googlesource.com/aom /home/claude/thirdparty/aom
git -C /home/claude/thirdparty/aom checkout v3.2.0

# Kvazaar: eigenes Android.mk (alle src/*.c ohne CLI, generic- und Platzhalter-Strategien),
#   CFLAGS -std=gnu99 -DKVZ_BIT_DEPTH=8 -DKVZ_VERSION_MAJOR=2 ... -fno-stack-protector
# libaom:
cmake /home/claude/thirdparty/aom -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=armeabi \
  -DANDROID_PLATFORM=android-12 -DANDROID_TOOLCHAIN=gcc -DCMAKE_BUILD_TYPE=Release \
  -DAOM_TARGET_CPU=generic -DCONFIG_AV1_DECODER=0 -DENABLE_DOCS=0 -DENABLE_EXAMPLES=0 \
  -DENABLE_TESTS=0 -DENABLE_TOOLS=0 -DCONFIG_RUNTIME_CPU_DETECT=0 \
  -DCMAKE_C_FLAGS="-std=gnu99 -fno-stack-protector -Dfloat_t=float -Ddouble_t=double"
ninja aom
# danach in native/: ndk-build, libs/armeabi/*.so nach app/src/main/jniLibs/armeabi/
```

Android 2.3 fehlen `log2` und `pthread_setname_np`; `heif.c` stellt beide bereit.
