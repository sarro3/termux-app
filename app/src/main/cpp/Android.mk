LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-prefix-remap
LOCAL_SRC_FILES := termux-prefix-remap.c
LOCAL_LDFLAGS := -Wl,--export-dynamic
include $(BUILD_SHARED_LIBRARY)
