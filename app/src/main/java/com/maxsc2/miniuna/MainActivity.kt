package com.maxsc2.miniuna

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.animation.AlphaAnimation
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var mascot: TextView
    private lateinit var tts: TextToSpeech
    private val voiceRequest = 700
    private val micPermission = 701

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        output = findViewById(R.id.output)
        status = findViewById(R.id.status)
        mascot = findViewById(R.id.mascot)
        tts = TextToSpeech(this, this)

        findViewById<Button>(R.id.mic).setOnClickListener { listen() }
        animateMascot(false)
    }

    private fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), micPermission)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Слушаю…")
        }
        startActivityForResult(intent, voiceRequest)
        status.text = "MINI-UNA  •  слушаю"
        animateMascot(true)
    }

    @Deprecated("Android callback API kept deliberately simple for MVP")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != voiceRequest) return
        animateMascot(false)
        status.text = "MINI-UNA  •  offline"
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text.isNullOrBlank()) {
            respond("Я не расслышала команду.")
            return
        }
        handle(text.trim())
    }

    private fun handle(raw: String) {
        val text = raw.lowercase(Locale.getDefault())
        when {
            text.contains("время") || text.contains("который час") -> {
                val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                respond("Сейчас $now.")
            }
            text.contains("дата") || text.contains("какое сегодня число") -> {
                val now = SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date())
                respond("Сегодня $now.")
            }
            text.startsWith("открой ") || text.startsWith("зайди на ") -> {
                val target = raw.substringAfter(" ", "").trim()
                val url = if (target.startsWith("http")) target else "https://www.google.com/search?q=" + Uri.encode(target)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                respond("Открываю.")
            }
            text.startsWith("посчитай ") || text.startsWith("вычисли ") -> {
                val expr = raw.substringAfter(" ").trim()
                val result = calculate(expr)
                respond(result)
            }
            text.startsWith("заметка ") || text.startsWith("запиши ") -> {
                val note = raw.substringAfter(" ").trim()
                getPreferences(MODE_PRIVATE).edit().putString("last_note", note).apply()
                respond("Записала. Последняя заметка сохранена на устройстве.")
            }
            text.contains("что ты умеешь") || text.contains("помощь") -> {
                respond("Я умею слушать голос, говорить, показывать время и дату, считать простые выражения, открывать сайты и сохранять заметку.")
            }
            else -> respond("Пока я не знаю эту команду. Для сложного диалога сюда позже подключим Needle и облачную модель.")
        }
    }

    private fun calculate(expr: String): String {
        val cleaned = expr.replace(",", ".").replace(" ", "")
        val match = Regex("^(-?\\d+(?:\\.\\d+)?)([+\\-*/])(-?\\d+(?:\\.\\d+)?)$").find(cleaned)
            ?: return "Я умею считать пока только выражения вроде 12 плюс 7 в формате 12+7."
        val a = match.groupValues[1].toDouble()
        val op = match.groupValues[2]
        val b = match.groupValues[3].toDouble()
        if (op == "/" && b == 0.0) return "На ноль делить не буду. Даже я умею отказывать."
        val r = when (op) {
            "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> a / b
            else -> return "Неизвестная операция."
        }
        val shown = if (r == r.roundToInt().toDouble()) r.roundToInt().toString() else "%.4f".format(Locale.US, r)
        return "Ответ: $shown."
    }

    private fun respond(text: String) {
        output.text = text
        if (::tts.isInitialized && tts.isSpeaking) tts.stop()
        if (::tts.isInitialized) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mini_una")
    }

    private fun animateMascot(active: Boolean) {
        mascot.clearAnimation()
        if (active) {
            mascot.startAnimation(AlphaAnimation(0.55f, 1f).apply { duration = 650; repeatCount = AlphaAnimation.INFINITE; repeatMode = AlphaAnimation.REVERSE })
            mascot.text = "◉ᴗ◉"
        } else mascot.text = "◉‿◉"
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            tts.language = Locale("ru", "RU")
            status.text = "MINI-UNA  •  offline"
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
