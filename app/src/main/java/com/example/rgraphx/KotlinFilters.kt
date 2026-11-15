package com.example.rgraphx

import android.graphics.Bitmap

/**
 * Filtros escritos em Kotlin puro (sem NDK, sem OpenCV).
 * Servem de base de comparação para o benchmark contra o C++.
 */
object KotlinFilters {

    /**
     * Tempos de uma execução, em ms.
     * [loopMs]: só o laço que calcula os pixels (comparável ao laço do C++).
     * [totalMs]: laço + cópia dos pixels do Bitmap para a JVM e de volta
     * (getPixels/setPixels), que é o custo extra de processar imagem no Kotlin.
     */
    class Timing(val totalMs: Double, val loopMs: Double)

    /**
     * Grayscale com a MESMA fórmula do native-lib.cpp:
     * gray = (R * 77 + G * 150 + B * 29) >> 8
     * Altera o [bitmap] (precisa ser mutável).
     */
    fun grayscale(bitmap: Bitmap): Timing {
        val width = bitmap.width
        val height = bitmap.height

        val start = System.nanoTime()

        // O Kotlin não acessa a memória do Bitmap direto: precisa copiar para um IntArray
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val loopStart = System.nanoTime()
        for (i in pixels.indices) {
            val color = pixels[i] // formato ARGB: 0xAARRGGBB
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF
            val gray = (r * 77 + g * 150 + b * 29) ushr 8
            pixels[i] = (color and ALPHA_MASK) or (gray shl 16) or (gray shl 8) or gray
        }
        val loopEnd = System.nanoTime()

        // E copiar de volta para o Bitmap
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val end = System.nanoTime()
        return Timing(
            totalMs = (end - start) / 1_000_000.0,
            loopMs = (loopEnd - loopStart) / 1_000_000.0,
        )
    }

    private const val ALPHA_MASK = 0xFF000000.toInt()
}
