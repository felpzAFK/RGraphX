package com.example.rgraphx

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Tela de Stress Test:
 *  - START: escalonamento de resolução (640x480 até 8000x6000) com imagem de teste sintética;
 *  - DATASET: as mesmas medições com fotos escolhidas da galeria;
 *  - MEMORY LEAK: vazamento proposital de Mats no C++ e a versão corrigida;
 *  - EXPORTAR CSV: planilha com todos os resultados (abre no Excel / Google Sheets).
 */
class StressTestActivity : AppCompatActivity() {

    /** Uma linha da planilha (escalonamento ou dataset). Tempos null = não executou. */
    private class StressRow(
        val test: String,
        val label: String,
        val width: Int,
        val height: Int,
        val cppMs: Double?,
        val kotlinTotalMs: Double?,
        val kotlinLoopMs: Double?,
        val cannyMs: Double?,
        val nativePeakMb: Double,
        val javaPeakMb: Double,
        val status: String,
    ) {
        val megapixels: Double get() = width.toLong() * height / 1_000_000.0

        /** Quantas vezes o Kotlin (total) foi mais lento que o C++. */
        val factor: Double?
            get() = if (cppMs != null && kotlinTotalMs != null && cppMs > 0) kotlinTotalMs / cppMs else null
    }

    /** Uma medição do teste de memory leak. */
    private class LeakRow(val phase: String, val iteration: Int, val nativeMb: Double, val leakedMb: Double)

    /** Pico de memória observado durante uma medição (amostrado pelo cronômetro). */
    private class PeakTracker {
        var nativeMb = 0.0
        var javaMb = 0.0

        fun reset() {
            nativeMb = MemoryProbe.nativeHeapMb()
            javaMb = MemoryProbe.javaHeapMb()
        }

        fun sample() {
            nativeMb = max(nativeMb, MemoryProbe.nativeHeapMb())
            javaMb = max(javaMb, MemoryProbe.javaHeapMb())
        }
    }

    private lateinit var btnStart: Button
    private lateinit var otherButtons: List<Button>
    private lateinit var txtStep: TextView
    private lateinit var txtLiveTime: TextView
    private lateinit var txtLiveNative: TextView
    private lateinit var txtLiveJava: TextView
    private lateinit var txtResults: TextView
    private lateinit var txtConsole: TextView
    private lateinit var progress: ProgressBar

    private val stressRows = mutableListOf<StressRow>()
    private val leakRows = mutableListOf<LeakRow>()
    private val peak = PeakTracker()
    private var currentJob: Job? = null

    private val pickDataset =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isNotEmpty()) runDataset(uris) else log("[SYSTEM] Nenhuma imagem escolhida.")
        }

    private val createCsv =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) exportCsv(uri) else log("[SYSTEM] Exportação cancelada.")
        }

    companion object {
        private const val TAG = "RGraphX"

        /** Resoluções do teste de escalonamento (VGA → 8000x6000 = 48 MP). */
        private val RESOLUTIONS = listOf(
            640 to 480,
            1280 to 720,
            1920 to 1080,
            2560 to 1440,
            3840 to 2160,
            5472 to 3648,
            8000 to 6000,
        )
        private const val PATTERN_SEED = 42
        private const val DATASET_MAX_SIDE = 8000
        private const val CANNY_LOW = 80.0
        private const val CANNY_HIGH = 160.0

        private const val LEAK_WIDTH = 1920
        private const val LEAK_HEIGHT = 1080
        private const val LEAK_ITERATIONS = 10
        private const val LEAK_STEP_DELAY_MS = 400L // dá tempo do Profiler desenhar a "escada"

        private const val TICK_MS = 50L
        private const val LIVE_HEAP_INTERVAL_MS = 500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_stress_test)

        val basePadding = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.stressRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                bars.left + basePadding, bars.top + basePadding,
                bars.right + basePadding, bars.bottom + basePadding,
            )
            insets
        }

        btnStart = findViewById(R.id.btnStart)
        val btnDataset = findViewById<Button>(R.id.btnDataset)
        val btnLeak = findViewById<Button>(R.id.btnLeak)
        val btnExport = findViewById<Button>(R.id.btnExport)
        otherButtons = listOf(btnDataset, btnLeak, btnExport)
        txtStep = findViewById(R.id.txtStep)
        txtLiveTime = findViewById(R.id.txtLiveTime)
        txtLiveNative = findViewById(R.id.txtLiveNative)
        txtLiveJava = findViewById(R.id.txtLiveJava)
        txtResults = findViewById(R.id.txtResults)
        txtConsole = findViewById(R.id.txtStressConsole)
        progress = findViewById(R.id.progressStress)

        val chip = findViewById<TextView>(R.id.chipStressBuild)
        chip.text = if (isDebugBuild()) "DEBUG" else "RELEASE"
        chip.setTextColor(
            ContextCompat.getColor(this, if (isDebugBuild()) R.color.lab_amber else R.color.lab_green)
        )

        log("[SYSTEM] Stress Lab pronto. Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        log("[SYSTEM] Limite da heap Java: ${fmt(MemoryProbe.javaHeapMaxMb())} MB")
        if (isDebugBuild()) log("[AVISO] Build DEBUG: C++ sem otimização. Para o relatório, use a variante release.")
        NativeLib.loadError?.let { log("[ERRO NDK] Biblioteca nativa não carregou: $it") }

        btnStart.setOnClickListener {
            val job = currentJob
            if (job != null && job.isActive) {
                log("[STOP] Parando após a etapa atual...")
                job.cancel()
            } else {
                runScaling()
            }
        }
        btnDataset.setOnClickListener {
            pickDataset.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        btnLeak.setOnClickListener { runLeakTest() }
        btnExport.setOnClickListener {
            if (stressRows.isEmpty() && leakRows.isEmpty()) {
                log("[SYSTEM] Nada para exportar: rode algum teste antes.")
            } else {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                createCsv.launch("rgraphx_resultados_$stamp.csv")
            }
        }

        // Heap ao vivo enquanto a tela está visível
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    txtLiveNative.text = "${fmt(MemoryProbe.nativeHeapMb())} MB"
                    txtLiveJava.text = "${fmt(MemoryProbe.javaHeapMb())} MB"
                    delay(LIVE_HEAP_INTERVAL_MS)
                }
            }
        }
    }

    override fun onDestroy() {
        // Garantia: nunca deixar as Mats do teste de leak presas ao sair da tela
        if (NativeLib.isReady) NativeLib.releaseLeakedMats()
        super.onDestroy()
    }

    // ================================================================ testes

    /** Escalonamento de resolução com a imagem de teste sintética (reproduzível). */
    private fun runScaling() = runTest("Escalonamento de resolução") {
        val total = RESOLUTIONS.size
        log("[START] Escalonamento: $total resoluções × (Grayscale C++, Grayscale Kotlin, Canny)")
        RESOLUTIONS.forEachIndexed { index, (w, h) ->
            setProgress(index, total)
            val prefix = "[${index + 1}/$total] ${w}x$h"
            var allocated: Bitmap? = null
            try {
                val bitmap = timedStep("$prefix alocando ${fmt(w.toLong() * h * 4 / MemoryProbe.BYTES_PER_MB)} MB...") {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                }
                allocated = bitmap
                val fill = timedStep("$prefix gerando imagem de teste...") {
                    NativeLib.fillTestPattern(bitmap, PATTERN_SEED)
                }
                if (fill < 0) {
                    addRow(failedRow("Escalonamento", "${w}x$h", w, h, NativeLib.errorMessage(fill)))
                } else {
                    addRow(measure("Escalonamento", "${w}x$h", bitmap, prefix))
                }
            } catch (e: OutOfMemoryError) {
                addRow(failedRow("Escalonamento", "${w}x$h", w, h, "FALHOU: sem memória para alocar a imagem"))
            } finally {
                allocated?.recycle() // devolve a memória nativa na hora, sem esperar o GC
            }
            settleMemory()
        }
        setProgress(total, total)
    }

    /** As mesmas medições com fotos reais escolhidas pelo usuário (o "dataset"). */
    private fun runDataset(uris: List<Uri>) = runTest("Dataset (${uris.size} imagens)") {
        val total = uris.size
        log("[START] Dataset: $total imagem(ns), lado maior limitado a $DATASET_MAX_SIDE px")
        uris.forEachIndexed { index, uri ->
            setProgress(index, total)
            val name = withContext(Dispatchers.IO) { ImageLoader.displayName(this@StressTestActivity, uri) }
            val prefix = "[${index + 1}/$total] $name"
            var allocated: Bitmap? = null
            try {
                val bitmap = timedStep("$prefix abrindo...") {
                    ImageLoader.decode(this@StressTestActivity, uri, DATASET_MAX_SIDE)
                }
                allocated = bitmap
                addRow(measure("Dataset", name, bitmap, prefix))
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                addRow(failedRow("Dataset", name, 0, 0, "FALHOU: sem memória ao abrir"))
            } catch (e: Exception) {
                addRow(failedRow("Dataset", name, 0, 0, "FALHOU: ${e.javaClass.simpleName}"))
            } finally {
                allocated?.recycle()
            }
            settleMemory()
        }
        setProgress(total, total)
    }

    /**
     * Teste de memory leak:
     *  Fase A — aloca Mats no C++ e NÃO libera: o heap nativo sobe em "escada".
     *  Correção — releaseLeakedMats(): o heap volta ao normal.
     *  Fase B — mesma carga liberando cada Mat (mat.release()): o heap fica estável.
     * Rode com o Android Profiler aberto (aba Memory) para capturar o gráfico.
     */
    private fun runLeakTest() = runTest("Memory Leak") {
        leakRows.clear()
        val totalSteps = LEAK_ITERATIONS * 2
        val mbPerMat = LEAK_WIDTH.toLong() * LEAK_HEIGHT * 4 / MemoryProbe.BYTES_PER_MB
        val baseline = MemoryProbe.nativeHeapMb()
        leakRows += LeakRow("Início", 0, baseline, 0.0)
        log("[LEAK] Fase A — SEM release: $LEAK_ITERATIONS Mats de ${LEAK_WIDTH}x$LEAK_HEIGHT (${fmt(mbPerMat)} MB cada)")

        try {
            for (i in 1..LEAK_ITERATIONS) {
                setProgress(i - 1, totalSteps)
                val leaked = timedStep("[LEAK A $i/$LEAK_ITERATIONS] alocando sem liberar...") {
                    NativeLib.leakMats(LEAK_WIDTH, LEAK_HEIGHT, 1)
                }
                if (leaked < 0) throw IllegalStateException("leakMats falhou (veja o Logcat)")
                val heap = MemoryProbe.nativeHeapMb()
                leakRows += LeakRow("A: sem release", i, heap, leaked / MemoryProbe.BYTES_PER_MB)
                log("[LEAK A] iteração $i: heap nativo ${fmt(heap)} MB | preso: ${fmt(leaked / MemoryProbe.BYTES_PER_MB)} MB")
                renderTables()
                delay(LEAK_STEP_DELAY_MS)
            }
            txtStep.text = "> Fase A concluída: memória presa. Corrigindo em 2 s..."
            delay(2000)
        } finally {
            // A correção roda mesmo se o teste for interrompido: nunca deixa a memória presa
            val freed = NativeLib.releaseLeakedMats()
            val heap = MemoryProbe.nativeHeapMb()
            leakRows += LeakRow("Correção: release", 0, heap, 0.0)
            log("[FIX] releaseLeakedMats(): ${fmt(freed / MemoryProbe.BYTES_PER_MB)} MB liberados. Heap nativo: ${fmt(heap)} MB")
            renderTables()
        }

        delay(1000)
        log("[LEAK] Fase B — COM release: mesma carga, liberando cada Mat")
        for (i in 1..LEAK_ITERATIONS) {
            setProgress(LEAK_ITERATIONS + i - 1, totalSteps)
            val ms = timedStep("[LEAK B $i/$LEAK_ITERATIONS] alocando e liberando...") {
                NativeLib.allocAndReleaseMats(LEAK_WIDTH, LEAK_HEIGHT, 1)
            }
            if (ms < 0) throw IllegalStateException("allocAndReleaseMats falhou (veja o Logcat)")
            val heap = MemoryProbe.nativeHeapMb()
            leakRows += LeakRow("B: com release", i, heap, 0.0)
            log("[LEAK B] iteração $i: heap nativo ${fmt(heap)} MB")
            renderTables()
            delay(LEAK_STEP_DELAY_MS)
        }
        setProgress(totalSteps, totalSteps)

        val peakA = leakRows.filter { it.phase.startsWith("A") }.maxOfOrNull { it.nativeMb } ?: baseline
        val peakB = leakRows.filter { it.phase.startsWith("B") }.maxOfOrNull { it.nativeMb } ?: baseline
        report("[LEAK] Resumo: início ${fmt(baseline)} MB | pico SEM release ${fmt(peakA)} MB " +
            "(+${fmt(peakA - baseline)} MB) | pico COM release ${fmt(peakB)} MB (+${fmt(peakB - baseline)} MB)")
    }

    // ================================================================ medição

    /** Mede Grayscale C++, Grayscale Kotlin e Canny na mesma imagem (alterada no lugar). */
    private suspend fun measure(test: String, label: String, bitmap: Bitmap, prefix: String): StressRow {
        peak.reset()
        val problems = mutableListOf<String>()

        val cpp = timedStep("$prefix Grayscale C++...") { NativeLib.grayscale(bitmap) }
        val cppMs = if (cpp >= 0) cpp else null.also { problems += NativeLib.errorMessage(cpp) }

        // O filtro Kotlin precisa copiar a imagem inteira para a heap Java (IntArray):
        // em resoluções altas ele pode estourar o limite, e isso também é um resultado.
        var ktTotal: Double? = null
        var ktLoop: Double? = null
        try {
            val t = timedStep("$prefix Grayscale Kotlin...") { KotlinFilters.grayscale(bitmap) }
            ktTotal = t.totalMs
            ktLoop = t.loopMs
        } catch (e: OutOfMemoryError) {
            problems += "Kotlin: sem memória na heap Java"
            settleMemory()
        }

        val canny = timedStep("$prefix Canny (OpenCV)...") {
            NativeLib.cannyEdge(bitmap, CANNY_LOW, CANNY_HIGH)
        }
        val cannyMs = if (canny >= 0) canny else null.also { problems += NativeLib.errorMessage(canny) }

        peak.sample()
        val row = StressRow(
            test = test,
            label = label,
            width = bitmap.width,
            height = bitmap.height,
            cppMs = cppMs,
            kotlinTotalMs = ktTotal,
            kotlinLoopMs = ktLoop,
            cannyMs = cannyMs,
            nativePeakMb = peak.nativeMb,
            javaPeakMb = peak.javaMb,
            status = if (problems.isEmpty()) "OK" else problems.joinToString(" | "),
        )
        report(
            "$prefix → C++ ${opt(row.cppMs)} ms | KT ${opt(row.kotlinTotalMs)} ms | " +
                "Canny ${opt(row.cannyMs)} ms | pico nativo ${fmt(row.nativePeakMb)} MB | ${row.status}"
        )
        return row
    }

    private fun failedRow(test: String, label: String, w: Int, h: Int, status: String): StressRow {
        report("[FALHA] $label: $status")
        return StressRow(
            test, label, w, h, null, null, null, null,
            MemoryProbe.nativeHeapMb(), MemoryProbe.javaHeapMb(), status,
        )
    }

    /**
     * Roda [work] fora da Main Thread mostrando um cronômetro ao vivo na tela
     * e amostrando o pico de memória enquanto a etapa executa.
     */
    private suspend fun <T> timedStep(text: String, work: () -> T): T {
        txtStep.text = "> $text"
        val start = SystemClock.elapsedRealtime()
        val ticker = lifecycleScope.launch {
            while (true) {
                txtLiveTime.text = "${SystemClock.elapsedRealtime() - start} ms"
                peak.sample()
                delay(TICK_MS)
            }
        }
        try {
            return withContext(Dispatchers.Default) { work() }
        } finally {
            ticker.cancel()
            txtLiveTime.text = "${SystemClock.elapsedRealtime() - start} ms"
        }
    }

    /** Pede ao GC para limpar entre etapas, para uma etapa não "herdar" lixo da anterior. */
    private suspend fun settleMemory() {
        withContext(Dispatchers.Default) { Runtime.getRuntime().gc() }
        delay(150)
    }

    // ================================================================ execução / UI

    private fun runTest(title: String, block: suspend () -> Unit) {
        if (currentJob?.isActive == true) return
        if (!NativeLib.isReady) {
            log("[ERRO NDK] Biblioteca nativa indisponível.")
            return
        }
        // LAZY: guarda o Job em currentJob ANTES de começar a rodar
        // (com Main.immediate, um teste que falhasse na hora deixaria o botão travado em PARAR)
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            setRunning(true)
            val start = SystemClock.elapsedRealtime()
            try {
                block()
                log("[OK] $title concluído em ${fmt((SystemClock.elapsedRealtime() - start) / 1000.0)} s.")
                txtStep.text = "> $title: CONCLUÍDO"
            } catch (e: CancellationException) {
                log("[STOP] $title interrompido.")
                throw e
            } catch (e: OutOfMemoryError) {
                log("[ERRO] $title: memória insuficiente.")
            } catch (e: Exception) {
                log("[ERRO] $title: ${e.javaClass.simpleName} ${e.message ?: ""}")
            } finally {
                currentJob = null
                setRunning(false)
            }
        }
        currentJob = job
        job.start()
    }

    private fun setRunning(running: Boolean) {
        btnStart.text = if (running) "■ PARAR" else "▶ START · ESCALONAMENTO 640x480 → 8000x6000"
        otherButtons.forEach { it.isEnabled = !running }
        if (!running && txtStep.text.endsWith("...")) txtStep.text = "> INTERROMPIDO"
    }

    private fun setProgress(done: Int, total: Int) {
        progress.progress = if (total == 0) 0 else done * 100 / total
    }

    private fun addRow(row: StressRow) {
        stressRows += row
        renderTables()
    }

    /** Monta as tabelas em texto monoespaçado (colunas alinhadas). */
    private fun renderTables() {
        val sb = StringBuilder()
        if (stressRows.isNotEmpty()) {
            sb.append(
                "TESTE".padEnd(6) + "IMAGEM".padEnd(20) + "RESOLUÇÃO".padEnd(11) + "MP".padStart(6) +
                    "C++ ms".padStart(10) + "KT ms".padStart(10) + "CANNY ms".padStart(10) +
                    "KT/C++".padStart(8) + "PICO MB".padStart(9) + "  STATUS\n"
            )
            for (r in stressRows) {
                sb.append(
                    (if (r.test == "Dataset") "DATA" else "ESC").padEnd(6) +
                        r.label.take(19).padEnd(20) +
                        "${r.width}x${r.height}".padEnd(11) +
                        fmt(r.megapixels).padStart(6) +
                        opt(r.cppMs).padStart(10) +
                        opt(r.kotlinTotalMs).padStart(10) +
                        opt(r.cannyMs).padStart(10) +
                        (r.factor?.let { fmt(it) + "x" } ?: "—").padStart(8) +
                        fmt(r.nativePeakMb).padStart(9) +
                        "  " + r.status + "\n"
                )
            }
        }
        if (leakRows.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("MEMORY LEAK".padEnd(20) + "ITER".padStart(5) + "HEAP NATIVO MB".padStart(16) + "PRESO MB".padStart(10) + "\n")
            for (r in leakRows) {
                sb.append(
                    r.phase.padEnd(20) + r.iteration.toString().padStart(5) +
                        fmt(r.nativeMb).padStart(16) + fmt(r.leakedMb).padStart(10) + "\n"
                )
            }
        }
        txtResults.text = if (sb.isEmpty()) "Nenhum teste executado ainda." else sb.toString()
    }

    // ================================================================ CSV (planilha)

    private fun exportCsv(uri: Uri) {
        lifecycleScope.launch {
            try {
                val csv = buildCsv()
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                        ?: throw IllegalStateException("não foi possível abrir o arquivo")
                }
                log("[CSV] Planilha salva: ${stressRows.size} medições + ${leakRows.size} linhas de leak.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("[ERRO] Falha ao salvar o CSV: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * CSV no padrão do Excel em português: separador ";" e vírgula decimal.
     * O BOM no início faz o Excel reconhecer os acentos (UTF-8).
     */
    private fun buildCsv(): String {
        val build = if (isDebugBuild()) "debug" else "release"
        val device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        val sb = StringBuilder("﻿")
        sb.append(
            "teste;imagem;largura;altura;megapixels;cpp_grayscale_ms;kotlin_total_ms;kotlin_laco_ms;" +
                "canny_ms;fator_kotlin_cpp;pico_heap_nativo_mb;pico_heap_java_mb;status;build;dispositivo\n"
        )
        for (r in stressRows) {
            sb.append(
                listOf(
                    csv(r.test), csv(r.label), r.width.toString(), r.height.toString(),
                    br(r.megapixels), br(r.cppMs), br(r.kotlinTotalMs), br(r.kotlinLoopMs),
                    br(r.cannyMs), br(r.factor), br(r.nativePeakMb), br(r.javaPeakMb),
                    csv(r.status), build, csv(device),
                ).joinToString(";")
            ).append('\n')
        }
        if (leakRows.isNotEmpty()) {
            sb.append("\nfase_memory_leak;iteracao;heap_nativo_mb;memoria_presa_mb\n")
            for (r in leakRows) {
                sb.append(listOf(csv(r.phase), r.iteration.toString(), br(r.nativeMb), br(r.leakedMb)).joinToString(";"))
                    .append('\n')
            }
        }
        return sb.toString()
    }

    private val ptBr: Locale = Locale.forLanguageTag("pt-BR")

    /** Número com vírgula decimal (vazio se não houver valor). */
    private fun br(value: Double?): String = value?.let { String.format(ptBr, "%.2f", it) } ?: ""

    /** Texto entre aspas, escapando aspas internas. */
    private fun csv(text: String): String = "\"" + text.replace("\"", "\"\"").replace('\n', ' ') + "\""

    // ================================================================ log

    private fun opt(value: Double?): String = value?.let { fmt(it) } ?: "—"

    /** Console + Logcat (tag RGraphX). */
    private fun report(message: String) {
        log(message)
        Log.i(TAG, message)
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        txtConsole.append("[$time] $message\n")
        val scroll = txtConsole.parent as? ScrollView
        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
