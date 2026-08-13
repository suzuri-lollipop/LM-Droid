#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define TAG "Whisper_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suzuri_lmdroid_data_stt_WhisperNative_init(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGD("Loading Whisper model from: %s", path);

    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;

    struct whisper_context * ctx = whisper_init_from_file_with_params(path, params);

    env->ReleaseStringUTFChars(model_path, path);

    if (ctx == nullptr) {
        LOGE("Failed to initialize Whisper context");
        return 0;
    }

    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_suzuri_lmdroid_data_stt_WhisperNative_full(JNIEnv *env, jobject thiz, jlong context, jfloatArray samples) {
    struct whisper_context * ctx = reinterpret_cast<struct whisper_context *>(context);
    if (!ctx) {
        LOGE("Whisper context is null in full()");
        return -1;
    }

    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    jsize len = env->GetArrayLength(samples);
    LOGD("Running inference on %d samples", len);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 4;
    params.print_realtime = false;
    params.print_progress = false;

    int ret = whisper_full(ctx, params, pcm, len);

    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    LOGD("Inference finished with code %d", ret);

    return ret;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_suzuri_lmdroid_data_stt_WhisperNative_getNSegments(JNIEnv *env, jobject thiz, jlong context) {
    struct whisper_context * ctx = reinterpret_cast<struct whisper_context *>(context);
    if (!ctx) return 0;
    return whisper_full_n_segments(ctx);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_suzuri_lmdroid_data_stt_WhisperNative_getSegmentText(JNIEnv *env, jobject thiz, jlong context, jint index) {
    struct whisper_context * ctx = reinterpret_cast<struct whisper_context *>(context);
    if (!ctx) return nullptr;
    const char * text = whisper_full_get_segment_text(ctx, index);
    return env->NewStringUTF(text);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suzuri_lmdroid_data_stt_WhisperNative_free(JNIEnv *env, jobject thiz, jlong context) {
    struct whisper_context * ctx = reinterpret_cast<struct whisper_context *>(context);
    if (ctx) {
        whisper_free(ctx);
    }
}
