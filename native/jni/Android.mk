LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := rawcore
LOCAL_SRC_FILES := rawcore.c
# ARM instead of Thumb-1: all registers and conditional execution for the bit work of decoding
LOCAL_ARM_MODE := arm
LOCAL_LDLIBS := -lz
LOCAL_CFLAGS := -O3 -Wall -fno-strict-aliasing -fno-stack-protector

include $(BUILD_SHARED_LIBRARY)

# Root commands on the camera's Linux host (see rootshell.c)
include $(CLEAR_VARS)
LOCAL_MODULE := omkroot
LOCAL_SRC_FILES := rootshell.c
LOCAL_LDLIBS := -ldl
LOCAL_CFLAGS := -O2 -Wall
include $(BUILD_SHARED_LIBRARY)

# ---- HEIC/AVIF encoder (proof of concept), third party code built outside the repo ----
# Kvazaar (BSD 3-clause) and libaom 3.2.0 (BSD 2-clause), see native/THIRD_PARTY.md
THIRD := /home/claude/thirdparty
include $(CLEAR_VARS)
LOCAL_MODULE := kvazaar_prebuilt
LOCAL_SRC_FILES := /home/claude/kvzbuild/obj/local/armeabi/libkvazaar.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := aom_prebuilt
LOCAL_SRC_FILES := /home/claude/aom320b/libaom.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := omkheif
LOCAL_SRC_FILES := heif.c
LOCAL_C_INCLUDES := $(THIRD)/kvazaar/src $(THIRD)/aom
LOCAL_CFLAGS := -O2 -Wall -std=gnu99 -fno-stack-protector
LOCAL_STATIC_LIBRARIES := kvazaar_prebuilt aom_prebuilt
LOCAL_LDLIBS := -lm
LOCAL_LDFLAGS := -Wl,--gc-sections
include $(BUILD_SHARED_LIBRARY)
