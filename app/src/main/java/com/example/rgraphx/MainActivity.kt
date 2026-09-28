package com.example.rgraphx

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var consoleOutput: TextView
    private lateinit var imgLab: ImageView

    companion object {
        init {
            try { System.loadLibrary("rgraphx_native") }
            catch (e: UnsatisfiedLinkError) { println("Erro C++") }
        }
    }

    external fun runNativeStressTest(): String
    external fun processImageReal(bitmap: Bitmap): String

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
        consoleOutput = findViewById(R.id.txtConsoleOutput)
        imgLab = findViewById(R.id.imgLab) // Ligamos a imagem aqui

        consoleOutput.text = "[SYSTEM] - CORE_READY\n"

        btnProcess.setOnClickListener {
            logToConsole("Enviando matriz de pixels para NDK (C++)...")
            val drawable = imgLab.drawable as? BitmapDrawable
            if (drawable != null) {
                try {
                    val mutableBitmap = drawable.bitmap.copy(Bitmap.Config.ARGB_8888, true)

                    val result = processImageReal(mutableBitmap)

                    imgLab.setImageBitmap(mutableBitmap)
                    logToConsole(result)
                } catch (e: Exception) {
                    logToConsole("[ERRO] Falha no processamento NDK.")
                }
            } else {
                logToConsole("[ERRO] Imagem não encontrada na UI.")
            }
        }

        btnEdge.setOnClickListener {
            logToConsole("Kernel Canny Edge não engatilhado. A aguardar OpenCV.")
        }

        btnStress.setOnClickListener {
            logToConsole("Iniciando requisição JNI para C++...")
            try {
                logToConsole(runNativeStressTest())
            } catch (e: Exception) {
                logToConsole("[ERRO NDK] Falha de stress test.")
            }
        }
    }

    private fun logToConsole(message: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        consoleOutput.append("[${timeFormat.format(Date())}] - $message\n")
    }
}