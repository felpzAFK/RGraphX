package com.example.rgraphx

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Imagem escolhida pelo usuário. Fica fora da Activity para sobreviver
 * se a tela for recriada (ex.: o sistema matar e restaurar a Activity).
 */
object LabState {
    var originalBitmap: Bitmap? = null
    var originalName: String = "rgraphicon.png"
}

class MainActivity : AppCompatActivity() {

    private lateinit var consoleOutput: TextView
    private lateinit var txtTelemetry: TextView
    private lateinit var txtLastExec: TextView
    private lateinit var lblLastExec: TextView
    private lateinit var txtResolution: TextView
    private lateinit var txtNativeHeap: TextView
    private lateinit var txtJavaHeap: TextView
    private lateinit var txtImageLabel: TextView
    private lateinit var imgLab: ImageView
    private lateinit var actionButtons: List<Button>

    /** Seletor de fotos do Android: não precisa pedir permissão de armazenamento. */
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) loadImage(uri) else logToConsole("[SYSTEM] Nenhuma imagem escolhida.")
        }

    /** Resultado de um filtro nativo: a imagem processada (ou null) e o tempo em ms (negativo = erro). */
    private class FilterResult(val bitmap: Bitmap?, val ms: Double)

    /** Média e mínimo de uma série de medições, em ms. */
    private class Stats(values: List<Double>) {
        val avg = values.average()
        val min = values.min()
    }

    /** Resultado do benchmark. Se [errorCode] não for null, o resto não vale. */
    private class BenchmarkResult(
        val errorCode: Double? = null,
        val bitmap: Bitmap? = null,
        val cpp: Stats? = null,
        val kotlinLoop: Stats? = null,
        val kotlinTotal: Stats? = null,
    )

    companion object {
        private const val TAG = "RGraphX"
        private const val CANNY_LOW = 80.0
        private const val CANNY_HIGH = 160.0
        private const val BENCH_RUNS = 5
        private const val MAX_SIDE = 4096 // lado maior das fotos carregadas na tela principal
        private const val TELEMETRY_INTERVAL_MS = 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Margem de 12dp + espaço das barras do sistema (status/navegação)
        val basePadding = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                bars.left + basePadding, bars.top + basePadding,
                bars.right + basePadding, bars.bottom + basePadding,
            )
            insets
        }

        consoleOutput = findViewById(R.id.txtConsoleOutput)
        txtTelemetry = findViewById(R.id.txtTelemetry)
        txtLastExec = findViewById(R.id.txtLastExec)
        lblLastExec = findViewById(R.id.lblLastExec)
        txtResolution = findViewById(R.id.txtResolution)
        txtNativeHeap = findViewById(R.id.txtNativeHeap)
        txtJavaHeap = findViewById(R.id.txtJavaHeap)
        txtImageLabel = findViewById(R.id.txtImageLabel)
        imgLab = findViewById(R.id.imgLab)

        val btnPick = findViewById<Button>(R.id.btnPick)
        val btnProcess = findViewById<Button>(R.id.btnProcess)
        val btnKotlin = findViewById<Button>(R.id.btnKotlin)
        val btnEdge = findViewById<Button>(R.id.btnEdge)
        val btnBenchmark = findViewById<Button>(R.id.btnBenchmark)
        val btnStress = findViewById<Button>(R.id.btnStress)
        val btnReset = findViewById<Button>(R.id.btnReset)
        val btnLab = findViewById<Button>(R.id.btnLab)
        actionButtons = listOf(btnPick, btnProcess, btnKotlin, btnEdge, btnBenchmark, btnStress, btnReset, btnLab)

        // Imagem inicial: a escolhida antes (se a Activity foi recriada) ou a do layout
        val original = LabState.originalBitmap
            ?: (imgLab.drawable as? BitmapDrawable)?.bitmap?.also { LabState.originalBitmap = it }
        if (original != null) showOriginal(original)

        setupStatusChips()
        consoleOutput.text = ""
        logToConsole("[SYSTEM] CORE_READY")
        logToConsole("[SYSTEM] Build: " + if (isDebugBuild()) "DEBUG" else "RELEASE")
        original?.let { logToConsole("[SYSTEM] Imagem: ${LabState.originalName} (${it.width}x${it.height})") }
        NativeLib.loadError?.let { logToConsole("[ERRO NDK] Biblioteca nativa não carregou: $it") }

        btnPick.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        btnProcess.setOnClickListener {
            runNativeFilter("Grayscale (C++)") { bitmap -> NativeLib.grayscale(bitmap) }
        }

        btnKotlin.setOnClickListener { runKotlinGrayscale() }

        btnEdge.setOnClickListener {
            runNativeFilter("Canny Edge (OpenCV)") { bitmap ->
                NativeLib.cannyEdge(bitmap, CANNY_LOW, CANNY_HIGH)
            }
        }

        btnBenchmark.setOnClickListener { runBenchmark() }

        btnStress.setOnClickListener {
            if (!nativeReady()) return@setOnClickListener
            logToConsole("Iniciando requisição JNI para C++...")
            launchJob {
                // Fora da Main Thread: a tela não congela durante o teste
                val message = withContext(Dispatchers.Default) { NativeLib.runCpuStress() }
                logToConsole(message)
                setTelemetry("CPU_STRESS: OK")
            }
        }

        btnReset.setOnClickListener {
            LabState.originalBitmap?.let {
                showOriginal(it)
                logToConsole("[SYSTEM] Imagem original restaurada.")
                setTelemetry("RESET")
            }
        }

        btnLab.setOnClickListener {
            startActivity(Intent(this, StressTestActivity::class.java))
        }

        startLiveTelemetry()
    }

    // ---------------------------------------------------------------- imagem

    private fun loadImage(uri: Uri) {
        logToConsole("[SYSTEM] Carregando imagem...")
        launchJob {
            val name = withContext(Dispatchers.IO) { ImageLoader.displayName(this@MainActivity, uri) }
            val bitmap = withContext(Dispatchers.IO) { ImageLoader.decode(this@MainActivity, uri, MAX_SIDE) }
            LabState.originalBitmap = bitmap // os filtros sempre partem daqui
            LabState.originalName = name
            showOriginal(bitmap)
            logToConsole("[SYSTEM] Imagem carregada: $name (${bitmap.width}x${bitmap.height})")
            setTelemetry("IMAGE_LOADED: ${bitmap.width}x${bitmap.height}")
        }
    }

    private fun showOriginal(bitmap: Bitmap) {
        imgLab.setImageBitmap(bitmap)
        txtResolution.text = "${bitmap.width}x${bitmap.height}"
        txtImageLabel.text = "ORIGINAL · ${bitmap.width}x${bitmap.height}"
    }

    private fun showResult(bitmap: Bitmap, label: String) {
        imgLab.setImageBitmap(bitmap)
        txtImageLabel.text = "$label · ${bitmap.width}x${bitmap.height}"
    }

    // ---------------------------------------------------------------- filtros

    /**
     * Copia a imagem original, aplica um filtro nativo em background
     * e mostra o resultado e o tempo medido pelo C++.
     */
    private fun runNativeFilter(label: String, filter: (Bitmap) -> Double) {
        val source = originalOrLog() ?: return
        if (!nativeReady()) return

        logToConsole("Enviando matriz de pixels para NDK (C++): $label...")
        launchJob {
            val result = withContext(Dispatchers.Default) {
                // Cópia mutável: o original nunca é alterado
                val working = source.copy(Bitmap.Config.ARGB_8888, true)
                    ?: return@withContext FilterResult(null, NativeLib.ERR_COPY)
                FilterResult(working, filter(working))
            }

            if (result.bitmap == null || result.ms < 0) {
                logToConsole(NativeLib.errorMessage(result.ms))
            } else {
                showResult(result.bitmap, label.uppercase(Locale.ROOT))
                logToConsole("[C++] $label aplicado. Tempo nativo: ${fmt(result.ms)} ms")
                setLastExec(label, result.ms)
            }
        }
    }

    /** O mesmo Grayscale, mas em Kotlin puro (sem NDK). */
    private fun runKotlinGrayscale() {
        val source = originalOrLog() ?: return

        logToConsole("Processando pixels em Kotlin puro (JVM/ART)...")
        launchJob {
            val result = withContext(Dispatchers.Default) {
                val working = source.copy(Bitmap.Config.ARGB_8888, true)
                    ?: return@withContext null
                working to KotlinFilters.grayscale(working)
            }

            if (result == null) {
                logToConsole(NativeLib.errorMessage(NativeLib.ERR_COPY))
            } else {
                val (bitmap, t) = result
                showResult(bitmap, "GRAYSCALE (KOTLIN)")
                logToConsole(
                    "[KT] Grayscale (Kotlin) aplicado. Tempo: ${fmt(t.totalMs)} ms " +
                        "(laço ${fmt(t.loopMs)} ms + cópia de pixels ${fmt(t.totalMs - t.loopMs)} ms)"
                )
                setLastExec("Grayscale (Kotlin)", t.totalMs)
            }
        }
    }

    /**
     * Benchmark Kotlin x C++ com o mesmo Grayscale.
     * 1 rodada de aquecimento (descartada: JIT do Kotlin, caches da CPU) e depois
     * [BENCH_RUNS] rodadas intercaladas C++ / Kotlin, para que variações de
     * frequência/temperatura da CPU afetem os dois lados por igual.
     */
    private fun runBenchmark() {
        val source = originalOrLog() ?: return
        if (!nativeReady()) return

        logToConsole(
            "[BEN] Iniciando: ${source.width}x${source.height}, " +
                "1 aquecimento + $BENCH_RUNS rodadas..."
        )
        setTelemetry("BENCHMARK: RUNNING...")
        launchJob {
            val result = withContext(Dispatchers.Default) { benchmark(source) }

            if (result.errorCode != null) {
                logToConsole(NativeLib.errorMessage(result.errorCode))
                return@launchJob
            }
            val cpp = result.cpp!!
            val ktLoop = result.kotlinLoop!!
            val ktTotal = result.kotlinTotal!!

            result.bitmap?.let { showResult(it, "BENCHMARK (GRAYSCALE)") }
            report("[BEN] Build ${if (isDebugBuild()) "DEBUG" else "RELEASE"} | " +
                "imagem ${source.width}x${source.height} | $BENCH_RUNS rodadas")
            report("[BEN] C++ (NDK)      média ${fmt(cpp.avg)} ms | mín ${fmt(cpp.min)} ms")
            report("[BEN] Kotlin (laço)  média ${fmt(ktLoop.avg)} ms | mín ${fmt(ktLoop.min)} ms")
            report("[BEN] Kotlin (total) média ${fmt(ktTotal.avg)} ms | mín ${fmt(ktTotal.min)} ms")
            report("[BEN] Laço: " + compare(cpp.avg, ktLoop.avg))
            report("[BEN] Total: " + compare(cpp.avg, ktTotal.avg))
            if (isDebugBuild()) {
                report("[BEN] Aviso: build DEBUG compila o C++ sem otimização. " +
                    "Para o relatório, rode a variante release.")
            }
            setLastExec("Bench C++ (média)", cpp.avg)
            setTelemetry(
                "LAST_BENCH: C++ ${fmt(cpp.avg)} ms | KT ${fmt(ktTotal.avg)} ms " +
                    "(${fmt(ktTotal.avg / cpp.avg)}x)"
            )
        }
    }

    /** Executa as medições. Roda em background (Dispatchers.Default). */
    private fun benchmark(source: Bitmap): BenchmarkResult {
        // Uma cópia para cada lado. Rodar o filtro de novo sobre a imagem já cinza
        // custa o mesmo tempo: os dois laços não têm desvios que dependam do valor do pixel.
        val cppBitmap = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: return BenchmarkResult(errorCode = NativeLib.ERR_COPY)
        val ktBitmap = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: return BenchmarkResult(errorCode = NativeLib.ERR_COPY)

        // Aquecimento (descartado)
        val warmUp = NativeLib.grayscale(cppBitmap)
        if (warmUp < 0) return BenchmarkResult(errorCode = warmUp)
        KotlinFilters.grayscale(ktBitmap)

        val cppTimes = ArrayList<Double>(BENCH_RUNS)
        val ktLoopTimes = ArrayList<Double>(BENCH_RUNS)
        val ktTotalTimes = ArrayList<Double>(BENCH_RUNS)
        repeat(BENCH_RUNS) {
            val ms = NativeLib.grayscale(cppBitmap)
            if (ms < 0) return BenchmarkResult(errorCode = ms)
            cppTimes += ms

            val t = KotlinFilters.grayscale(ktBitmap)
            ktLoopTimes += t.loopMs
            ktTotalTimes += t.totalMs
        }
        ktBitmap.recycle() // libera a memória nativa da cópia do Kotlin na hora

        return BenchmarkResult(
            bitmap = cppBitmap,
            cpp = Stats(cppTimes),
            kotlinLoop = Stats(ktLoopTimes),
            kotlinTotal = Stats(ktTotalTimes),
        )
    }

    /** Frase comparando os dois tempos, nos dois sentidos possíveis. */
    private fun compare(cppMs: Double, kotlinMs: Double): String = when {
        cppMs <= 0.0 -> "C++ abaixo da resolução do relógio (< 0,01 ms)"
        kotlinMs >= cppMs -> "C++ foi ${fmt(kotlinMs / cppMs)}x mais rápido"
        else -> "Kotlin foi ${fmt(cppMs / kotlinMs)}x mais rápido"
    }

    // ---------------------------------------------------------------- infraestrutura

    /**
     * Roda [work] numa coroutine com os botões travados, tratando os erros comuns.
     * O trabalho pesado dentro de [work] deve ir para Dispatchers.Default / IO.
     */
    private fun launchJob(work: suspend () -> Unit) {
        setButtonsEnabled(false)
        lifecycleScope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e // a Activity foi fechada: deixa a coroutine terminar normalmente
            } catch (e: OutOfMemoryError) {
                logToConsole("[ERRO] Memória insuficiente para processar a imagem.")
            } catch (e: Exception) {
                logToConsole("[ERRO] Falha: ${e.javaClass.simpleName} ${e.message ?: ""}")
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    /** Atualiza heap nativo/Java a cada segundo enquanto a tela está visível. */
    private fun startLiveTelemetry() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    txtNativeHeap.text = "${fmt(MemoryProbe.nativeHeapMb())} MB"
                    txtJavaHeap.text = "${fmt(MemoryProbe.javaHeapMb())} MB"
                    delay(TELEMETRY_INTERVAL_MS)
                }
            }
        }
    }

    private fun setupStatusChips() {
        val chipNdk = findViewById<TextView>(R.id.chipNdk)
        val chipBuild = findViewById<TextView>(R.id.chipBuild)
        if (NativeLib.isReady) {
            chipNdk.text = "● NDK OK"
            chipNdk.setTextColor(ContextCompat.getColor(this, R.color.lab_green))
        } else {
            chipNdk.text = "● NDK ERRO"
            chipNdk.setTextColor(ContextCompat.getColor(this, R.color.lab_red))
        }
        chipBuild.text = if (isDebugBuild()) "DEBUG" else "RELEASE"
        chipBuild.setTextColor(
            ContextCompat.getColor(this, if (isDebugBuild()) R.color.lab_amber else R.color.lab_green)
        )
    }

    private fun setLastExec(label: String, ms: Double) {
        lblLastExec.text = "ÚLTIMA: ${label.uppercase(Locale.ROOT)}"
        txtLastExec.text = "${fmt(ms)} ms"
        setTelemetry("LAST_EXEC: $label = ${fmt(ms)} ms")
    }

    private fun setTelemetry(status: String) {
        txtTelemetry.text = "> $status"
    }

    private fun originalOrLog(): Bitmap? {
        val source = LabState.originalBitmap
        if (source == null) logToConsole("[ERRO] Imagem não encontrada na UI.")
        return source
    }

    private fun nativeReady(): Boolean {
        if (NativeLib.isReady) return true
        logToConsole("[ERRO NDK] Biblioteca nativa indisponível.")
        return false
    }

    /** Escreve no console e no Logcat (tag RGraphX), para copiar os números depois. */
    private fun report(message: String) {
        logToConsole(message)
        Log.i(TAG, message)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        actionButtons.forEach { it.isEnabled = enabled }
    }

    private fun logToConsole(message: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        consoleOutput.append("[${timeFormat.format(Date())}] $message\n")
        // O painel é pequeno: rola até a última linha
        val scroll = consoleOutput.parent as? ScrollView
        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
