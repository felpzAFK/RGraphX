package com.example.rgraphx

import android.graphics.Bitmap

/**
 * Ponte JNI com o C++ (app/src/main/cpp/native-lib.cpp).
 * Fica num objeto próprio para as duas telas (laboratório e stress test) usarem.
 */
object NativeLib {

    /** null = biblioteca carregada; senão, a mensagem do erro de carregamento. */
    val loadError: String? = try {
        System.loadLibrary("rgraphx_native")
        null
    } catch (e: UnsatisfiedLinkError) {
        e.message ?: "UnsatisfiedLinkError"
    }

    val isReady: Boolean get() = loadError == null

    // Códigos de erro (mantenha em sincronia com native-lib.cpp)
    const val ERR_BITMAP_INFO = -1.0
    const val ERR_BITMAP_FORMAT = -2.0
    const val ERR_BITMAP_LOCK = -3.0
    const val ERR_OPENCV = -4.0
    const val ERR_COPY = -100.0

    /** Laço de 50 milhões de ciclos em C++ (teste de CPU). */
    external fun runCpuStress(): String

    /** Grayscale em C++ puro. Devolve o tempo em ms, ou um código de erro negativo. */
    external fun grayscale(bitmap: Bitmap): Double

    /** Canny Edge com OpenCV. Devolve o tempo em ms, ou um código de erro negativo. */
    external fun cannyEdge(bitmap: Bitmap, lowThreshold: Double, highThreshold: Double): Double

    /** Preenche o Bitmap com um padrão de teste determinístico. Devolve ms ou erro negativo. */
    external fun fillTestPattern(bitmap: Bitmap, seed: Int): Double

    /** MEMORY LEAK PROPOSITAL: aloca Mats e não libera. Devolve o total vazado em bytes (-1 = erro). */
    external fun leakMats(width: Int, height: Int, count: Int): Long

    /** Correção: libera as Mats vazadas. Devolve os bytes liberados. */
    external fun releaseLeakedMats(): Long

    /** Versão correta: aloca e libera cada Mat. Devolve o tempo em ms (-1 = erro). */
    external fun allocAndReleaseMats(width: Int, height: Int, count: Int): Double

    fun errorMessage(code: Double): String = when (code.toInt()) {
        -1 -> "[ERRO NDK] Falha ao ler a imagem."
        -2 -> "[ERRO NDK] Formato de cores não suportado."
        -3 -> "[ERRO NDK] Falha ao bloquear memória."
        -4 -> "[ERRO OpenCV] Exceção durante o processamento (veja o Logcat, tag RGraphX)."
        -100 -> "[ERRO] Falha ao copiar a imagem."
        else -> "[ERRO NDK] Falha desconhecida (código $code)."
    }
}
