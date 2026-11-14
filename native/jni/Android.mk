LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := rawcore
LOCAL_SRC_FILES := rawcore.c
LOCAL_CFLAGS := -O3 -Wall -fno-strict-aliasing -fno-stack-protector

include $(BUILD_SHARED_LIBRARY)
