#include <jni.h>
#include <string>
#include <chrono>
#include <android/bitmap.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_rgraphx_MainActivity_runNativeStressTest(JNIEnv* env, jobject /* this */) {
    auto start = std::chrono::high_resolution_clock::now();
    volatile long long dummy = 0;
    for(int i = 0; i < 50000000; ++i) { dummy += i; }
    auto end = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();

    std::string result = "[C++] Stress CPU (50M ciclos) finalizado. Tempo: " + std::to_string(duration) + "ms";
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_rgraphx_MainActivity_processImageReal(JNIEnv* env, jobject, jobject bitmap) {
    auto start = std::chrono::high_resolution_clock::now();

    AndroidBitmapInfo info;
    void* pixels;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return env->NewStringUTF("[ERRO NDK] Falha ao ler a imagem.");
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return env->NewStringUTF("[ERRO NDK] Formato de cores não suportado.");
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return env->NewStringUTF("[ERRO NDK] Falha ao bloquear memória.");

    uint32_t* line = (uint32_t*)pixels;
    int totalPixels = info.width * info.height;

    for (int i = 0; i < totalPixels; ++i) {
        uint32_t color = line[i];
        uint8_t r = (color & 0x00FF0000) >> 16;
        uint8_t g = (color & 0x0000FF00) >> 8;
        uint8_t b = (color & 0x000000FF);

        uint8_t gray = (r * 77 + g * 150 + b * 29) >> 8;

        line[i] = (color & 0xFF000000) | (gray << 16) | (gray << 8) | gray;
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    auto end = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();

    std::string result = "[C++] Filtro Grayscale real aplicado. Tempo GPU/CPU: " + std::to_string(duration) + "ms.";
    return env->NewStringUTF(result.c_str());
}