LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_C_FLAGS += -DANDROID_NDK -DDISABLE_IMPORTGL \
    -w -std=gnu99 -O3 -fno-strict-aliasing -fprefetch-loop-arrays \
    -DAVOID_TABLES -DANDROID_TILE_BASED_DECODE -DANDROID_ARMV6_IDCT

LOCAL_LDLIBS += -llog -ljnigraphics -lm
APP_STL := stlport_static

LOCAL_MODULE := timg
LOCAL_SRC_FILES := \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\pq.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\timg.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\aes\aes_core.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\aes\aes_ige.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\aes\aes_misc.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\armv6_idct.S \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jcapimin.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jcapistd.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jccoefct.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jccolor.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jcdctmgr.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jchuff.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jcinit.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jcmainct.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jcmarker.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jcmaster.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jcomapi.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jcparam.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jcphuff.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jcprepct.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jcsample.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jctrans.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdapimin.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdapistd.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdatadst.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdatasrc.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdcoefct.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdcolor.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jddctmgr.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdhuff.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdinput.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdmainct.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdmarker.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdmaster.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdmerge.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdphuff.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdpostct.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdsample.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jdtrans.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jerror.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jfdctflt.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jfdctfst.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jfdctint.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jidctflt.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jidctfst.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jidctint.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jidctred.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jmemmgr.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jmemnobs.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jquant1.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jquant2.c \
    C:\Users\viila\workspace\telegram.s\app\src\main\jni\libjpeg\jutils.c \

LOCAL_C_INCLUDES += C:\Users\viila\workspace\telegram.s\app\src\main\jni
LOCAL_C_INCLUDES += C:\Users\viila\workspace\telegram.s\app\src\beta\jni
LOCAL_C_INCLUDES += C:\Users\viila\workspace\telegram.s\app\src\debug\jni
LOCAL_C_INCLUDES += C:\Users\viila\workspace\telegram.s\app\src\betaDebug\jni

include $(BUILD_SHARED_LIBRARY)
