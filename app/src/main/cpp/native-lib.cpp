// RGraphX — código nativo (C++ / NDK / OpenCV)
// Todas as funções aqui são chamadas pelo objeto Kotlin com.example.rgraphx.NativeLib.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#define LOG_TAG "RGraphX"

namespace {

// Códigos de erro devolvidos ao Kotlin (valores negativos; sucesso = tempo em ms >= 0).
// Mantenha em sincronia com NativeLib.errorMessage() no Kotlin.
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

// ---- Teste de memory leak ----
// Mats "esquecidas" de propósito: simulam o erro clássico de alocar memória nativa
// e nunca liberar (o que o mat.release() / sair do escopo evitaria).
// Ficam guardadas aqui só para o app conseguir limpar depois e não travar o celular.
std::vector<cv::Mat> gLeakedMats;
std::mutex gLeakMutex;

int64_t leakedBytesLocked() {
    int64_t total = 0;
    for (const auto& m : gLeakedMats) total += static_cast<int64_t>(m.total() * m.elemSize());
    return total;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_rgraphx_NativeLib_runCpuStress(JNIEnv* env, jobject /* this */) {
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
Java_com_example_rgraphx_NativeLib_grayscale(JNIEnv* env, jobject /* this */, jobject bitmap) {
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
Java_com_example_rgraphx_NativeLib_cannyEdge(JNIEnv* env, jobject /* this */, jobject bitmap,
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

// Preenche o Bitmap com um padrão de teste determinístico (ruído + anéis + grade),
// cheio de bordas e texturas. Assim o stress test roda em qualquer resolução
// (até 8000x6000) sem depender de baixar imagens, e o resultado é reproduzível.
// Devolve o tempo em ms (não entra nas medições), ou um código de erro negativo.
extern "C" JNIEXPORT jdouble JNICALL
Java_com_example_rgraphx_NativeLib_fillTestPattern(JNIEnv* env, jobject /* this */, jobject bitmap,
                                                  jint seed) {
    AndroidBitmapInfo info;
    const int check = checkBitmap(env, bitmap, info);
    if (check != 0) return check;

    BitmapLock lock(env, bitmap);
    if (!lock.ok()) return kErrBitmapLock;

    const auto start = std::chrono::steady_clock::now();

    const int64_t cx = info.width / 2;
    const int64_t cy = info.height / 2;
    // Tamanho dos anéis proporcional à imagem: o padrão "parece o mesmo" em todas as resoluções
    const int64_t ringScale = std::max<int64_t>(1, (static_cast<int64_t>(info.width) * info.width) / 4096);
    const uint32_t gridStep = std::max<uint32_t>(8, info.width / 64);

    auto* base = static_cast<uint8_t*>(lock.pixels());
    for (uint32_t y = 0; y < info.height; ++y) {
        uint8_t* p = base + static_cast<size_t>(y) * info.stride;
        const int64_t dy = static_cast<int64_t>(y) - cy;
        for (uint32_t x = 0; x < info.width; ++x, p += 4) {
            // Hash inteiro simples: ruído "aleatório" mas sempre igual para o mesmo (x, y, seed)
            uint32_t h = x * 374761393u + y * 668265263u + static_cast<uint32_t>(seed) * 2246822519u;
            h = (h ^ (h >> 13)) * 1274126177u;
            h ^= h >> 16;

            const int64_t dx = static_cast<int64_t>(x) - cx;
            const auto rings = static_cast<uint8_t>(((dx * dx + dy * dy) / ringScale) & 0xFF);
            const bool grid = (x % gridStep) == 0 || (y % gridStep) == 0;

            const uint8_t noise = static_cast<uint8_t>(h >> 24);
            p[0] = grid ? 255 : static_cast<uint8_t>((rings >> 1) + (noise >> 1));
            p[1] = grid ? 255 : static_cast<uint8_t>(((x ^ y) & 0xFF) >> 1 | (noise & 0x80));
            p[2] = grid ? 255 : static_cast<uint8_t>(255 - rings);
            p[3] = 255;
        }
    }

    return elapsedMs(start);
}

// MEMORY LEAK PROPOSITAL: aloca [count] Mats de width x height (RGBA) e NÃO libera.
// Devolve quantos bytes estão "vazados" no total.
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_rgraphx_NativeLib_leakMats(JNIEnv* /* env */, jobject /* this */,
                                           jint width, jint height, jint count) {
    std::lock_guard<std::mutex> guard(gLeakMutex);
    try {
        for (int i = 0; i < count; ++i) {
            cv::Mat mat(height, width, CV_8UC4);
            // Escreve na memória: sem isso o sistema nem chega a reservar as páginas na RAM
            mat.setTo(cv::Scalar(i & 0xFF, 0x40, 0x80, 0xFF));
            gLeakedMats.push_back(mat);  // a referência fica guardada: a memória nunca é liberada
        }
    } catch (const cv::Exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "leakMats: %s", e.what());
        return -1;
    }
    return static_cast<jlong>(leakedBytesLocked());
}

// A CORREÇÃO: limpa as Mats vazadas. Devolve quantos bytes foram liberados.
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_rgraphx_NativeLib_releaseLeakedMats(JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> guard(gLeakMutex);
    const int64_t freed = leakedBytesLocked();
    for (auto& m : gLeakedMats) m.release();
    gLeakedMats.clear();
    gLeakedMats.shrink_to_fit();
    return static_cast<jlong>(freed);
}

// VERSÃO CORRETA: mesmo trabalho do leakMats, mas cada Mat é liberada ao fim
// da iteração (mat.release() + RAII). Devolve o tempo em ms, ou -1 em erro.
extern "C" JNIEXPORT jdouble JNICALL
Java_com_example_rgraphx_NativeLib_allocAndReleaseMats(JNIEnv* /* env */, jobject /* this */,
                                                      jint width, jint height, jint count) {
    const auto start = std::chrono::steady_clock::now();
    try {
        for (int i = 0; i < count; ++i) {
            cv::Mat mat(height, width, CV_8UC4);
            mat.setTo(cv::Scalar(i & 0xFF, 0x40, 0x80, 0xFF));
            mat.release();  // libera na hora; o destrutor também liberaria ao sair do escopo
        }
    } catch (const cv::Exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "allocAndReleaseMats: %s", e.what());
        return -1.0;
    }
    return elapsedMs(start);
}
