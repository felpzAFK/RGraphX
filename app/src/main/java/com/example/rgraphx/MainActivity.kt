package com.example.rgraphx

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var consoleOutput: TextView
    private lateinit var txtTelemetry: TextView
    private lateinit var imgLab: ImageView
    private lateinit var actionButtons: List<Button>

    /** Imagem original: cada filtro parte sempre dela, então o benchmark é comparável. */
    private var originalBitmap: Bitmap? = null

    /** Resultado de um filtro: a imagem processada (ou null) e o tempo em ms (negativo = erro). */
    private class FilterResult(val bitmap: Bitmap?, val ms: Double)

    companion object {
        private const val CANNY_LOW = 80.0
        private const val CANNY_HIGH = 160.0
        private const val ERR_COPY = -100.0
        private const val TELEMETRY_HEADER =
            "> RGraphX // CORE_SYSTEM_READY\n> MEMORY_LEAK_TRACKING: ONLINE\n"

        /** Mensagem do erro de carregamento da lib nativa (null = carregou). */
        private var nativeLoadError: String? = null

        init {
            try {
                System.loadLibrary("rgraphx_native")
            } catch (e: UnsatisfiedLinkError) {
                nativeLoadError = e.message ?: "UnsatisfiedLinkError"
            }
        }
    }

    external fun runNativeStressTest(): String

    /** Grayscale em C++ puro. Devolve o tempo em ms, ou um código de erro negativo. */
    external fun processImageReal(bitmap: Bitmap): Double

    /** Canny Edge com OpenCV. Devolve o tempo em ms, ou um código de erro negativo. */
    external fun applyCannyEdge(bitmap: Bitmap, lowThreshold: Double, highThreshold: Double): Double

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val btnProcess = findViewById<Button>(R.id.btnProcess)
        val btnEdge = findViewById<Button>(R.id.btnEdge)
        val btnStress = findViewById<Button>(R.id.btnStress)
        actionButtons = listOf(btnProcess, btnEdge, btnStress)
        consoleOutput = findViewById(R.id.txtConsoleOutput)
        txtTelemetry = findViewById(R.id.txtTelemetry)
        imgLab = findViewById(R.id.imgLab)

        originalBitmap = (imgLab.drawable as? BitmapDrawable)?.bitmap

        consoleOutput.text = "[SYSTEM] - CORE_READY\n"
        updateTelemetry("AWAITING_EXECUTION...")
        nativeLoadError?.let { logToConsole("[ERRO NDK] Biblioteca nativa não carregou: $it") }

        btnProcess.setOnClickListener {
            runFilter("Grayscale (C++)") { bitmap -> processImageReal(bitmap) }
        }

        btnEdge.setOnClickListener {
            runFilter("Canny Edge (OpenCV)") { bitmap ->
                applyCannyEdge(bitmap, CANNY_LOW, CANNY_HIGH)
            }
        }

        btnStress.setOnClickListener {
            if (nativeLoadError != null) {
                logToConsole("[ERRO NDK] Biblioteca nativa indisponível.")
                return@setOnClickListener
            }
            setButtonsEnabled(false)
            logToConsole("Iniciando requisição JNI para C++...")
            lifecycleScope.launch {
                try {
                    // Fora da Main Thread: a tela não congela durante o teste
                    val message = withContext(Dispatchers.Default) { runNativeStressTest() }
                    logToConsole(message)
                } catch (e: Exception) {
                    logToConsole("[ERRO NDK] Falha de stress test.")
                } finally {
                    setButtonsEnabled(true)
                }
            }
        }
    }

    /**
     * Copia a imagem original, aplica [filter] em background (Dispatchers.Default)
     * e mostra o resultado e o tempo medido pelo C++ na interface.
     */
    private fun runFilter(label: String, filter: (Bitmap) -> Double) {
        val source = originalBitmap
        if (source == null) {
            logToConsole("[ERRO] Imagem não encontrada na UI.")
            return
        }
        if (nativeLoadError != null) {
            logToConsole("[ERRO NDK] Biblioteca nativa indisponível.")
            return
        }

        setButtonsEnabled(false)
        logToConsole("Enviando matriz de pixels para NDK (C++): $label...")

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    // Cópia mutável: o original nunca é alterado
                    val working = source.copy(Bitmap.Config.ARGB_8888, true)
                        ?: return@withContext FilterResult(null, ERR_COPY)
                    FilterResult(working, filter(working))
                }

                if (result.bitmap == null || result.ms < 0) {
                    logToConsole(nativeErrorMessage(result.ms))
                } else {
                    imgLab.setImageBitmap(result.bitmap)
                    val ms = String.format(Locale.US, "%.2f", result.ms)
                    logToConsole("[C++] $label aplicado. Tempo nativo: $ms ms")
                    updateTelemetry("LAST_EXEC: $label = $ms ms")
                }
            } catch (e: OutOfMemoryError) {
                logToConsole("[ERRO] Memória insuficiente para copiar a imagem.")
            } catch (e: Exception) {
                logToConsole("[ERRO] Falha no processamento NDK.")
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    // Mantenha em sincronia com os códigos em native-lib.cpp
    private fun nativeErrorMessage(code: Double): String = when (code.toInt()) {
        -1 -> "[ERRO NDK] Falha ao ler a imagem."
        -2 -> "[ERRO NDK] Formato de cores não suportado."
        -3 -> "[ERRO NDK] Falha ao bloquear memória."
        -4 -> "[ERRO OpenCV] Exceção durante o processamento (veja o Logcat, tag RGraphX)."
        -100 -> "[ERRO] Falha ao copiar a imagem original."
        else -> "[ERRO NDK] Falha desconhecida (código $code)."
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        actionButtons.forEach { it.isEnabled = enabled }
    }

    private fun updateTelemetry(status: String) {
        txtTelemetry.text = TELEMETRY_HEADER + "> " + status
    }

    private fun logToConsole(message: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        consoleOutput.append("[${timeFormat.format(Date())}] - $message\n")
        // O painel é pequeno: rola até a última linha
        val scroll = consoleOutput.parent as? ScrollView
        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
