#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <string>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#define LOG_TAG "RGraphX"

namespace {

// Códigos de erro devolvidos ao Kotlin (valores negativos; sucesso = tempo em ms >= 0).
// Mantenha em sincronia com nativeErrorMessage() na MainActivity.
constexpr double kErrBitmapInfo = -1.0;
constexpr double kErrBitmapFormat = -2.0;
constexpr double kErrBitmapLock = -3.0;
constexpr double kErrOpenCv = -4.0;

inline double elapsedMs(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start).count();
}

// Lê as informações do Bitmap e valida o formato. Retorna 0 se ok, ou um código de erro.
int checkBitmap(JNIEnv* env, jobject bitmap, AndroidBitmapInfo& info) {
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return static_cast<int>(kErrBitmapInfo);
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return static_cast<int>(kErrBitmapFormat);
    return 0;
}

// RAII: garante AndroidBitmap_unlockPixels em qualquer caminho de saída
// (retorno antecipado ou exceção), evitando Bitmap travado/vazamento.
class BitmapLock {
public:
    BitmapLock(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {
        locked_ = AndroidBitmap_lockPixels(env_, bitmap_, &pixels_) >= 0;
    }

    ~BitmapLock() {
        if (locked_) AndroidBitmap_unlockPixels(env_, bitmap_);
    }

    BitmapLock(const BitmapLock&) = delete;
    BitmapLock& operator=(const BitmapLock&) = delete;

    bool ok() const { return locked_; }
    void* pixels() const { return pixels_; }

private:
    JNIEnv* env_;
    jobject bitmap_;
    void* pixels_ = nullptr;
    bool locked_ = false;
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_rgraphx_MainActivity_runNativeStressTest(JNIEnv* env, jobject /* this */) {
    const auto start = std::chrono::steady_clock::now();
    volatile long long dummy = 0;
    for (int i = 0; i < 50000000; ++i) { dummy += i; }
    const double duration = elapsedMs(start);

    char buffer[128];
    snprintf(buffer, sizeof(buffer),
             "[C++] Stress CPU (50M ciclos) finalizado. Tempo: %.2f ms", duration);
    return env->NewStringUTF(buffer);
}

// Grayscale feito à mão, direto na memória do Bitmap (sem OpenCV).
// Devolve o tempo em ms, ou um código de erro negativo.
extern "C" JNIEXPORT jdouble JNICALL
Java_com_example_rgraphx_MainActivity_processImageReal(JNIEnv* env, jobject /* this */, jobject bitmap) {
    AndroidBitmapInfo info;
    const int check = checkBitmap(env, bitmap, info);
    if (check != 0) return check;

    BitmapLock lock(env, bitmap);
    if (!lock.ok()) return kErrBitmapLock;

    const auto start = std::chrono::steady_clock::now();

    auto* base = static_cast<uint8_t*>(lock.pixels());
    for (uint32_t y = 0; y < info.height; ++y) {
        // stride pode ser maior que width * 4, por isso andamos linha a linha
        uint8_t* p = base + static_cast<size_t>(y) * info.stride;
        for (uint32_t x = 0; x < info.width; ++x, p += 4) {
            // Em RGBA_8888 a ordem dos bytes na memória é R, G, B, A
            const uint8_t gray = static_cast<uint8_t>((p[0] * 77 + p[1] * 150 + p[2] * 29) >> 8);
            p[0] = gray;
            p[1] = gray;
            p[2] = gray;
        }
    }

    return elapsedMs(start);
}

// Detecção de bordas (Canny) com OpenCV, direto na memória do Bitmap.
// Devolve o tempo em ms, ou um código de erro negativo.
extern "C" JNIEXPORT jdouble JNICALL
Java_com_example_rgraphx_MainActivity_applyCannyEdge(JNIEnv* env, jobject /* this */, jobject bitmap,
                                                    jdouble lowThreshold, jdouble highThreshold) {
    AndroidBitmapInfo info;
    const int check = checkBitmap(env, bitmap, info);
    if (check != 0) return check;

    BitmapLock lock(env, bitmap);
    if (!lock.ok()) return kErrBitmapLock;

    const auto start = std::chrono::steady_clock::now();

    try {
        // Mat que apenas enxerga os pixels do Bitmap: não copia nem aloca a imagem inteira
        cv::Mat rgba(static_cast<int>(info.height), static_cast<int>(info.width), CV_8UC4,
                     lock.pixels(), info.stride);

        // Mats temporárias: liberadas automaticamente ao sair do escopo (RAII)
        cv::Mat gray;
        cv::Mat edges;
        cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);
        cv::GaussianBlur(gray, gray, cv::Size(5, 5), 1.4);  // reduz ruído antes do Canny
        cv::Canny(gray, edges, lowThreshold, highThreshold);

        // Escreve o resultado de volta no Bitmap (mesmo tamanho/tipo: reaproveita a memória)
        cv::cvtColor(edges, rgba, cv::COLOR_GRAY2RGBA);

        gray.release();
        edges.release();
    } catch (const cv::Exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "OpenCV: %s", e.what());
        return kErrOpenCv;
    }

    return elapsedMs(start);
}
