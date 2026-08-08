#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/bitmap.h>
#include <vector>
#include "stable-diffusion.h"

#define TAG "SD_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

struct ProgressContext {
    jobject callback;
    jmethodID methodId;
};

void progress_cb(int step, int steps, float time, void* data) {
    ProgressContext* ctx = static_cast<ProgressContext*>(data);
    if (!ctx || !ctx->callback || !ctx->methodId) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("Failed to attach thread for progress callback");
            return;
        }
        attached = true;
    }

    if (env) {
        float progress = static_cast<float>(step) / static_cast<float>(steps);
        env->CallVoidMethod(ctx->callback, ctx->methodId, progress);
    }

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_suzuri_lmdroid_data_network_StableDiffusionNative_loadModel(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGD("Loading model from: %s", path);

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = path;
    params.vae_path = ""; // Auto
    params.wtype = SD_TYPE_COUNT; // Auto
    params.backend = "cpu"; // Changed from vulkan

    sd_ctx_t* ctx = new_sd_ctx(&params);

    env->ReleaseStringUTFChars(model_path, path);

    if (ctx == nullptr) {
        LOGE("Failed to create stable-diffusion context");
        return 0;
    }

    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_suzuri_lmdroid_data_network_StableDiffusionNative_generateImage(
        JNIEnv *env, jobject thiz, jlong context, jstring prompt, jstring negative_prompt,
        jint width, jint height, jlong seed, jint sample_steps, jobject base_image,
        jfloat denoising_strength, jobject on_progress) {

    sd_ctx_t* ctx = reinterpret_cast<sd_ctx_t*>(context);
    if (!ctx) return nullptr;

    const char *p = env->GetStringUTFChars(prompt, nullptr);
    const char *np = env->GetStringUTFChars(negative_prompt, nullptr);

    sd_img_gen_params_t params;
    sd_img_gen_params_init(&params);
    params.prompt = p;
    params.negative_prompt = np;
    params.width = width;
    params.height = height;
    params.seed = seed;
    params.sample_params.sample_steps = sample_steps;
    params.strength = denoising_strength;

    // Handle I2I
    if (base_image != nullptr) {
        AndroidBitmapInfo info;
        void* pixels;
        if (AndroidBitmap_getInfo(env, base_image, &info) == 0 &&
            AndroidBitmap_lockPixels(env, base_image, &pixels) == 0) {

            params.init_image.width = info.width;
            params.init_image.height = info.height;
            params.init_image.channel = 3;
            params.init_image.data = new uint8_t[info.width * info.height * 3];

            // Convert RGBA_8888 to RGB_888
            uint8_t* src = static_cast<uint8_t*>(pixels);
            uint8_t* dst = params.init_image.data;
            for (int i = 0; i < info.width * info.height; ++i) {
                dst[i * 3 + 0] = src[i * 4 + 0]; // R
                dst[i * 3 + 1] = src[i * 4 + 1]; // G
                dst[i * 3 + 2] = src[i * 4 + 2]; // B
            }

            AndroidBitmap_unlockPixels(env, base_image);
        }
    }

    // Set progress callback
    ProgressContext prog_ctx;
    if (on_progress != nullptr) {
        jclass cb_class = env->GetObjectClass(on_progress);
        prog_ctx.callback = on_progress;
        prog_ctx.methodId = env->GetMethodID(cb_class, "invoke", "(F)V");
        sd_set_progress_callback(progress_cb, &prog_ctx);
    }

    sd_image_t* result_images = nullptr;
    int num_images = 0;
    bool success = generate_image(ctx, &params, &result_images, &num_images);

    // Cleanup input image data if any
    if (params.init_image.data) {
        delete[] params.init_image.data;
    }

    sd_set_progress_callback(nullptr, nullptr);
    env->ReleaseStringUTFChars(prompt, p);
    env->ReleaseStringUTFChars(negative_prompt, np);

    if (!success || num_images == 0 || result_images == nullptr) {
        LOGE("Generation failed");
        return nullptr;
    }

    // Convert sd_image_t to Bitmap
    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
    jmethodID create_bitmap_method = env->GetStaticMethodID(bitmap_class, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jfieldID argb_8888_field = env->GetStaticFieldID(config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb_8888_config = env->GetStaticObjectField(config_class, argb_8888_field);

    jobject bitmap = env->CallStaticObjectMethod(bitmap_class, create_bitmap_method, (jint)result_images[0].width, (jint)result_images[0].height, argb_8888_config);

    void* bitmap_pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &bitmap_pixels) == 0) {
        uint8_t* src = result_images[0].data;
        uint8_t* dst = static_cast<uint8_t*>(bitmap_pixels);
        for (int i = 0; i < result_images[0].width * result_images[0].height; ++i) {
            dst[i * 4 + 0] = src[i * 3 + 0]; // R
            dst[i * 4 + 1] = src[i * 3 + 1]; // G
            dst[i * 4 + 2] = src[i * 3 + 2]; // B
            dst[i * 4 + 3] = 255;           // A
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    }

    free_sd_images(result_images, num_images);

    return bitmap;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_suzuri_lmdroid_data_network_StableDiffusionNative_freeModel(JNIEnv *env, jobject thiz, jlong context) {
    LOGD("Freeing model context");
    sd_ctx_t* ctx = reinterpret_cast<sd_ctx_t*>(context);
    if (ctx) {
        free_sd_ctx(ctx);
    }
}
