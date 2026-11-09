package com.example.rgraphx

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.provider.OpenableColumns
import java.util.Locale

/** Leituras de memória para a telemetria e os testes. */
object MemoryProbe {

    /** Memória nativa alocada (malloc): pixels de Bitmap e Mats do OpenCV entram aqui. */
    fun nativeHeapMb(): Double = Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB

    /** Memória usada na heap da JVM/ART (objetos Kotlin, IntArray do filtro Kotlin etc.). */
    fun javaHeapMb(): Double {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
    }

    /** Limite da heap da JVM para este app. */
    fun javaHeapMaxMb(): Double = Runtime.getRuntime().maxMemory() / BYTES_PER_MB

    const val BYTES_PER_MB = 1024.0 * 1024.0
}

/** Abre imagens escolhidas pelo usuário (galeria / seletor de fotos). */
object ImageLoader {

    /**
     * Decodifica a imagem do [uri] como Bitmap de SOFTWARE (o C++ precisa acessar os pixels na RAM),
     * limitando o lado maior a [maxSide] px.
     */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // O padrão do ImageDecoder é HARDWARE (fica na GPU): o C++ não consegue travar esses pixels
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Editável: o stress test processa a imagem no lugar, sem gastar memória com cópia
                decoder.isMutableRequired = true
                val w = info.size.width
                val h = info.size.height
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        maxOf(1, (w * scale).toInt()),
                        maxOf(1, (h * scale).toInt()),
                    )
                }
            }
        } else {
            // Android 8.x: BitmapFactory (aqui a rotação EXIF da foto não é corrigida)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IllegalStateException("Falha ao decodificar a imagem")
        }
    }

    /** Nome do arquivo (ex.: IMG_2031.jpg), ou "imagem" se não der para descobrir. */
    fun displayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "imagem"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "imagem"
        }
    }
}

/** true = build debug (C++ compilado sem otimização, -O0). */
fun Context.isDebugBuild(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/** Número com 2 casas e ponto decimal (console). */
fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
