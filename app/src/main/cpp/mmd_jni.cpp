// JNI bridge for the NDK-native MMD renderer (Phase 4, method B). Kotlin side:
// MmdNative.kt / MmdNativeRenderer.kt. Parsing may run on any thread; every GL
// entry point (nativeInitGl / nativeSetTexture / nativeResize / nativeDrawFrame)
// must be called from the GLSurfaceView's renderer thread.
#include <jni.h>
#include <android/log.h>

#include <memory>
#include <string>

#include "mmd/mmd_engine.h"
#include "mmd/mmd_renderer.h"

#define JNI_LOG(...) __android_log_print(ANDROID_LOG_INFO, "MmdJni", __VA_ARGS__)

namespace {

JavaVM* g_jvm = nullptr;
jclass g_sjisClass = nullptr;
jmethodID g_decodeSjisMethod = nullptr;

// VMD names are Shift-JIS; Java's charset support beats bundling a multi-thousand-entry
// native table, so the parser calls back into MmdNative.decodeShiftJis.
std::string decodeSjis(const char* bytes, size_t len) {
    if (g_jvm == nullptr || g_decodeSjisMethod == nullptr) return std::string(bytes, len);
    JNIEnv* env = nullptr;
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return std::string(bytes, len);
    }
    jbyteArray array = env->NewByteArray(static_cast<jsize>(len));
    if (array == nullptr) return std::string(bytes, len);
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(bytes));
    std::string result;
    jstring str = static_cast<jstring>(env->CallStaticObjectMethod(g_sjisClass, g_decodeSjisMethod, array));
    if (str != nullptr) {
        const char* utf = env->GetStringUTFChars(str, nullptr);
        if (utf != nullptr) {
            result = utf;
            env->ReleaseStringUTFChars(str, utf);
        }
        env->DeleteLocalRef(str);
    }
    env->DeleteLocalRef(array);
    return result;
}

struct MmdContext {
    mmd::MmdEngine engine;
    mmd::MmdRenderer renderer;
    bool rendererInitialized = false;
    std::string lastError;
};

MmdContext* fromHandle(jlong handle) {
    return reinterpret_cast<MmdContext*>(handle);
}

std::string toStdString(JNIEnv* env, jstring str) {
    if (str == nullptr) return {};
    const char* chars = env->GetStringUTFChars(str, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(str, chars);
    return result;
}

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK && env != nullptr) {
        jclass cls = env->FindClass("com/suzuri/lmdroid/ui/character/MmdNative");
        if (cls != nullptr) {
            g_sjisClass = static_cast<jclass>(env->NewGlobalRef(cls));
            g_decodeSjisMethod = env->GetStaticMethodID(g_sjisClass, "decodeShiftJis", "([B)Ljava/lang/String;");
        }
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_suzuri_lmdroid_ui_character_MmdNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new MmdContext());
}

JNIEXPORT void JNICALL
Java_com_suzuri_lmdroid_ui_character_MmdNative_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete fromHandle(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_suzuri_lmdroid_ui_character_MmdNative_nativeLoadModel(JNIEnv* env, jclass, jlong handle, jstring pmxPath) {
    MmdContext* ctx = fromHandle(handle);
    if (ctx == nullptr) return JNI_FALSE;
    std::string error;
    bool ok = ctx->engine.loadModel(toStdString(env, pmxPath), &error);
    ctx->lastError = ok ? "" : error;
    if (!ok) JNI_LOG("loadModel failed: %s", error.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_suzuri_lmdroid_ui_character_MmdNative_nativeLoadMotion(JNIEnv* env, jclass, jlong handle, jstring vmdPath) {
    MmdContext* ctx = fromHandle(handle);
    if (ctx == nullptr || !ctx->engine.hasModel()) return JNI_FALSE;
    std::string error;
    bool ok = ctx->engine.loadMotion(toStdString(env, vmdPath), decodeSjis, &error);
    ctx->lastError = ok ? ctx->lastError : error;
    if (!ok) JNI_LOG("loadMotion failed: %s", error.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_suzuri_lmdroid_ui_character_MmdNative_nativeLastError(JNIEnv* env, jclass, jlong handle) {
    MmdContext* ctx = fromHandle(handle);
    return env->NewStringUTF(ctx != nullptr ? ctx->lastError.c_str() : "");
}

JNIEXPORT jint JNICALL
Java_com_suzuri_lmdroid_ui_character_MmdNative_nativeTextureCount(JNIEnv*, jclass, jlong handle) {
    MmdContext* ctx = fromHandle(handle);
    if (ctx == nullptr || !ctx->engine.hasModel()) return 0;
    return static_cast<jint>(ctx->engine.model().textures.size());
}

// Resolved absolute path (modelDir + relative entry) so Kotlin can decode it directly.
JNIEXPORT jstring JNICALL
Java_com_suzuri_lmdroid_ui_character_MmdNative_nativeTexturePath(JNIEnv* env, jclass, jlong handle, jint index) {
    MmdContext* ctx = fromHandle(handle);
    if (ctx == nullptr || !ctx->engine.hasModel()) return env->NewStringUTF("");
    const mmd::PmxModel& model = ctx->engine.model();
    if (index < 0 || index >= static_cast<jint>(model.textures.size())) return env->NewStringUTF("");
    const std::string& rel = model.textures[index];
    bool absolute = !rel.empty() && rel[0] == '/';
    std::string path = absolute ? rel : model.modelDir + rel;
    return env->NewStringUTF(path.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_suzuri_lmdroid_ui_character_MmdNative_nativeInitGl(JNIEnv*, jclass, jlong handle) {
    MmdContext* ctx = fromHandle(handle);
    if (ctx == nullptr || !ctx->engine.hasModel()) return JNI_FALSE;
    ctx->rendererInitialized = ctx->renderer.init(ctx->engine.model());
    return ctx->rendererInitialized ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_suzuri_lmdroid_ui_character_MmdNative_nativeSetTexture(JNIEnv* env, jclass, jlong handle,
                                                                jint index, jint width, jint height,
                                                                jintArray argbPixels) {
    MmdContext* ctx = fromHandle(handle);
    if (ctx == nullptr || !ctx->rendererInitialized || argbPixels == nullptr) return;
    jsize count = env->GetArrayLength(argbPixels);
    if (count < static_cast<jsize>(width) * height) return;
    jint* pixels = env->GetIntArrayElements(argbPixels, nullptr);
    if (pixels == nullptr) return;
    ctx->renderer.setTexture(index, width, height, reinterpret_cast<const uint32_t*>(pixels));
    env->ReleaseIntArrayElements(argbPixels, pixels, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_suzuri_lmdroid_ui_character_MmdNative_nativeResize(JNIEnv*, jclass, jlong handle, jint width, jint height) {
    MmdContext* ctx = fromHandle(handle);
    if (ctx != nullptr && ctx->rendererInitialized) ctx->renderer.resize(width, height);
}

JNIEXPORT void JNICALL
Java_com_suzuri_lmdroid_ui_character_MmdNative_nativeDrawFrame(JNIEnv*, jclass, jlong handle,
                                                               jfloat dt, jint state, jfloat mouthOpen,
                                                               jboolean lipSync) {
    MmdContext* ctx = fromHandle(handle);
    if (ctx == nullptr || !ctx->engine.hasModel() || !ctx->rendererInitialized) return;
    ctx->engine.update(dt, static_cast<mmd::CharacterState>(state), mouthOpen, lipSync == JNI_TRUE);
    ctx->renderer.draw(ctx->engine);
}

} // extern "C"
