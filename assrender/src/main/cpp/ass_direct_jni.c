#include <jni.h>
#include <stdio.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string.h>

#include "ass_direct.h"

#define LOG_TAG "assrender_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_io_github_assrender_AssDirectBridge_nativeInit(
        JNIEnv *env, jobject thiz,
        jint width, jint height, jfloat font_scale) {
    (void)env; (void)thiz;
    AssDirectContext *ctx = ass_direct_init(width, height, font_scale);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jint JNICALL
Java_io_github_assrender_AssDirectBridge_nativeLoadHeader(
        JNIEnv *env, jobject thiz,
        jlong handle, jbyteArray header_data) {
    (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    if (!ctx || !header_data) return -1;

    jsize size = (*env)->GetArrayLength(env, header_data);
    jbyte *data = (*env)->GetByteArrayElements(env, header_data, NULL);
    if (!data) return -1;

    int result = ass_direct_load_header(ctx, (const char *)data, size);
    (*env)->ReleaseByteArrayElements(env, header_data, data, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL
Java_io_github_assrender_AssDirectBridge_nativeAddFont(
        JNIEnv *env, jobject thiz,
        jlong handle, jstring name, jbyteArray font_data) {
    (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    if (!ctx || !font_data) return;

    const char *cname = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
    jsize size = (*env)->GetArrayLength(env, font_data);
    jbyte *data = (*env)->GetByteArrayElements(env, font_data, NULL);
    if (!data) {
        if (cname) (*env)->ReleaseStringUTFChars(env, name, cname);
        return;
    }

    ass_direct_add_font(ctx, cname, (const char *)data, size);

    (*env)->ReleaseByteArrayElements(env, font_data, data, JNI_ABORT);
    if (cname) (*env)->ReleaseStringUTFChars(env, name, cname);
}

JNIEXPORT jint JNICALL
Java_io_github_assrender_AssDirectBridge_nativeAddFontDir(
        JNIEnv *env, jobject thiz, jlong handle, jstring dir) {
    (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    if (!ctx || !dir) return 0;

    const char *cdir = (*env)->GetStringUTFChars(env, dir, NULL);
    if (!cdir) return 0;
    int added = ass_direct_add_font_dir(ctx, cdir);
    (*env)->ReleaseStringUTFChars(env, dir, cdir);
    return added;
}

JNIEXPORT void JNICALL
Java_io_github_assrender_AssDirectBridge_nativeFinalizeFonts(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    ass_direct_finalize_fonts((AssDirectContext *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_io_github_assrender_AssDirectBridge_nativeResize(
        JNIEnv *env, jobject thiz, jlong handle, jint width, jint height) {
    (void)env; (void)thiz;
    return ass_direct_resize((AssDirectContext *)(intptr_t)handle, width, height);
}

JNIEXPORT void JNICALL
Java_io_github_assrender_AssDirectBridge_nativeProcessChunk(
        JNIEnv *env, jobject thiz,
        jlong handle, jbyteArray data, jlong start_ms, jlong duration_ms) {
    (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    if (!ctx || !data) return;

    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return;

    ass_direct_process_chunk(ctx, (const char *)bytes, size, start_ms, duration_ms);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_io_github_assrender_AssDirectBridge_nativeProcessData(
        JNIEnv *env, jobject thiz,
        jlong handle, jbyteArray data) {
    (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    if (!ctx || !data) return;

    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return;

    ass_direct_process_data(ctx, (const char *)bytes, size);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL
Java_io_github_assrender_AssDirectBridge_nativeRender(
        JNIEnv *env, jobject thiz,
        jlong handle, jlong time_ms, jobject bitmap) {
    (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    if (!ctx || !bitmap) return JNI_FALSE;

    // The renderer writes width x height RGBA_8888 pixels; refuse any bitmap
    // that does not match so a stale or foreign bitmap can never be overrun.
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to query bitmap info");
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        (int)info.width != ass_direct_width(ctx) ||
        (int)info.height != ass_direct_height(ctx) ||
        info.stride < info.width * 4u) {
        LOGE("Bitmap mismatch: %ux%u format=%d stride=%u, expected %dx%d RGBA_8888",
             info.width, info.height, info.format, info.stride,
             ass_direct_width(ctx), ass_direct_height(ctx));
        return JNI_FALSE;
    }

    void *pixels = NULL;
    int ret = AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (ret != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        LOGE("Failed to lock bitmap pixels: %d", ret);
        return JNI_FALSE;
    }

    int has_content = ass_direct_render(ctx, time_ms, (uint8_t *)pixels, info.stride);

    AndroidBitmap_unlockPixels(env, bitmap);
    return has_content ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_assrender_AssDirectBridge_nativeFlush(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    ass_direct_flush(ctx);
}

JNIEXPORT void JNICALL
Java_io_github_assrender_AssDirectBridge_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    ass_direct_destroy(ctx);
}
