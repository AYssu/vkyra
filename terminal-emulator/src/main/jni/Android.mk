LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
LOCAL_LDFLAGS += "-Wl,-z,max-page-size=16384"
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
include $(BUILD_SHARED_LIBRARY)
