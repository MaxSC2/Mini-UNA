package com.maxsc2.miniuna

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var mascot: MascotView
    private lateinit var tts: TextToSpeech
    private lateinit var intentEngine: IntentEngine
    private lateinit var toolRegistry: ToolRegistry

    private val voiceRequest = 700
    private val micPermission = 701
    private val prefs by lazy { getSharedPreferences("mini_una", MODE_PRIVATE) }

    private val pages by lazy {
        listOf(
            findViewById<View>(R.id.homePage),
            findViewById<View>(R.id.toolsPage),
            findViewById<View>(R.id.notesPage),
            findViewById<View>(R.id.settingsPage)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        output = findViewById(R.id.output)
        status = findViewById(R.id.status)
        mascot = findViewById(R.id.mascot)
        tts = TextToSpeech(this, this)

        intentEngine = NeedleEngine(this, LocalIntentEngine())
        toolRegistry = ToolRegistry(this) { note ->
            prefs.edit().putString("last_note", note).apply()
            refreshNote()
        }

        setupNavigation()
        setupSettings()
        refreshNote()
        applySavedMascotState()
        updateModelStatus()
        showPage(0)
    }

    private fun setupNavigation() {
        findViewById<Button>(R.id.navHome).setOnClickListener { showPage(0) }
        findViewById<Button>(R.id.navTools).setOnClickListener { showPage(1) }
        findViewById<Button>(R.id.navNotes).setOnClickListener { showPage(2) }
        findViewById<Button>(R.id.navSettings).setOnClickListener { showPage(3) }
        findViewById<Button>(R.id.mic).setOnClickListener { listen() }

        findViewById<Button>(R.id.testTool).setOnClickListener {
            val sample = "который сейчас час"
            val result = intentEngine.classify(sample)
            findViewById<TextView>(R.id.toolStatus).text =
                "Router test: " + result.intent + " (" +
                    (result.confidence * 100).roundToInt() + "%)\n\n" +
                    "Needle runtime: " +
                    if ((intentEngine as? NeedleEngine)?.isNativeReady() == true) "native" else "fallback" +
                    "\n\nПопробуй голосом: «открой Telegram», «поставь таймер на 5 минут», «громче», «назад»."
        }

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.clearNote).setOnClickListener {
            prefs.edit().remove("last_note").apply()
            refreshNote()
            respond("Заметка очищена.")
        }
    }

    private fun showPage(index: Int) {
        pages.forEachIndexed { i, page ->
            page.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        val navs = listOf(R.id.navHome, R.id.navTools, R.id.navNotes, R.id.navSettings)
        navs.forEachIndexed { i, id ->
            findViewById<Button>(id).alpha = if (i == index) 1f else 0.55f
        }
    }

    private fun setupSettings() {
        val tracking = findViewById<SwitchCompat>(R.id.toggleTracking)
        val breathing = findViewById<SwitchCompat>(R.id.toggleBreathing)
        val voice = findViewById<SwitchCompat>(R.id.toggleVoice)

        tracking.isChecked = prefs.getBoolean("tracking", true)
        breathing.isChecked = prefs.getBoolean("breathing", true)
        voice.isChecked = prefs.getBoolean("voice", true)

        tracking.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("tracking", checked).apply()
            mascot.setTracking(checked)
        }
        breathing.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("breathing", checked).apply()
            mascot.setBreathing(checked)
        }
        voice.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("voice", checked).apply()
        }

        val emotions = listOf(
            "neutral" to "Спокойствие", "happy" to "Радость", "surprised" to "Удивление",
            "smirk" to "Ухмылка", "grin" to "Оскал", "shy" to "Смущение",
            "sad" to "Грусть", "angry" to "Злость", "sleepy" to "Сон"
        )
        val emotionBox = findViewById<LinearLayout>(R.id.emotionButtons)

        emotions.forEach { pair ->
            emotionBox.addView(Button(this).apply {
                text = pair.second
                isAllCaps = false
                alpha = if (pair.first == prefs.getString("emotion", "neutral")) 1f else 0.7f
                setOnClickListener {
                    prefs.edit().putString("emotion", pair.first).apply()
                    mascot.setEmotion(pair.first)
                    for (i in 0 until emotionBox.childCount) {
                        emotionBox.getChildAt(i).alpha = 0.55f
                    }
                    alpha = 1f
                    mascot.blink()
                }
            })
        }

        val palettes = listOf(
            Palette("Синяя сфера", "#1A84FC", "#489CFE", "#82BCFF", "#4A9DF8"),
            Palette("Ледяная", "#5B8CFF", "#83B8FF", "#D7E8FF", "#7FB6FF"),
            Palette("Фиолетовая", "#7C4DFF", "#A77BFF", "#D6C8FF", "#9B7CFF"),
            Palette("Розовая", "#FF4F9A", "#FF78B6", "#FFC1D9", "#FF70B0"),
            Palette("Мятная", "#00BFA6", "#3DD6C4", "#A8F3E7", "#35D8C4")
        )
        val paletteBox = findViewById<LinearLayout>(R.id.paletteButtons)
        palettes.forEach { palette ->
            paletteBox.addView(Button(this).apply {
                text = palette.name
                isAllCaps = false
                setOnClickListener {
                    applyPalette(palette)
                    prefs.edit().putString("palette", palette.name).apply()
                }
            })
        }
    }

    private fun applySavedMascotState() {
        mascot.setTracking(prefs.getBoolean("tracking", true))
        mascot.setBreathing(prefs.getBoolean("breathing", true))
        mascot.setEmotion(prefs.getString("emotion", "neutral") ?: "neutral")
        applyPalette(
            paletteByName(prefs.getString("palette", "Синяя сфера"))
                ?: paletteByName("Синяя сфера")!!
        )
    }

    private fun applyPalette(palette: Palette) {
        mascot.setPalette(
            Color.parseColor(palette.core),
            Color.parseColor(palette.mid),
            Color.parseColor(palette.rim),
            Color.parseColor(palette.aura)
        )
    }

    private fun paletteByName(name: String?): Palette? = listOf(
        Palette("Синяя сфера", "#1A84FC", "#489CFE", "#82BCFF", "#4A9DF8"),
        Palette("Ледяная", "#5B8CFF", "#83B8FF", "#D7E8FF", "#7FB6FF"),
        Palette("Фиолетовая", "#7C4DFF", "#A77BFF", "#D6C8FF", "#9B7CFF"),
        Palette("Розовая", "#FF4F9A", "#FF78B6", "#FFC1D9", "#FF70B0"),
        Palette("Мятная", "#00BFA6", "#3DD6C4", "#A8F3E7", "#35D8C4")
    ).firstOrNull { it.name == name }

    private data class Palette(
        val name: String,
        val core: String,
        val mid: String,
        val rim: String,
        val aura: String
    )

    private fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                micPermission
            )
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Слушаю…")
        }

        status.text = "MINI-UNA  •  слушаю"
        mascot.setEmotion("surprised")
        startActivityForResult(intent, voiceRequest)
    }

    @Deprecated("Simple Android speech callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != voiceRequest) return

        mascot.setEmotion(prefs.getString("emotion", "neutral") ?: "neutral")
        status.text = "MINI-UNA  •  обработка команды"

        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text.isNullOrBlank()) {
            respond("Я не расслышала команду.")
            return
        }

        handle(text.trim())
    }

    private fun handle(raw: String) {
        val result = intentEngine.classify(raw)
        val decision = SafetyPolicy.decide(result)

        when (decision) {
            SafetyPolicy.Decision.BLOCK -> {
                respond(
                    if (result.intent == "UNKNOWN") {
                        "Не нашла подходящего локального инструмента для этой команды."
                    } else {
                        "Эта команда сейчас недоступна."
                    }
                )
            }

            SafetyPolicy.Decision.CONFIRM -> showConfirmation(result)

            SafetyPolicy.Decision.ALLOW -> {
                val response = if (result.intent == "HELP") {
                    "Умею открывать приложения и настройки, искать в интернете, считать, ставить таймер, менять громкость, сохранять заметки и выполнять базовые системные действия через службу специальных возможностей."
                } else {
                    try {
                        toolRegistry.execute(result)
                    } catch (_: Throwable) {
                        "Android не смог выполнить эту команду."
                    }
                }

                if (response == null) {
                    respond("Инструмент для «" + result.intent + "» не подключён.")
                } else {
                    respond(response)
                }
            }
        }

        updateModelStatus()
    }

    private fun showConfirmation(result: IntentResult) {
        val actionText = result.intent.replace('_', ' ').lowercase(Locale.getDefault())

        AlertDialog.Builder(this)
            .setTitle("Нужно подтверждение")
            .setMessage(
                "Mini-UNA не выполнит действие автоматически.\n\n" +
                    "Действие: " + actionText +
                    "\nУверенность Needle: " + (result.confidence * 100).roundToInt() + "%" +
                    if (result.reasoning.isNotBlank()) "\n\n" + result.reasoning else ""
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выполнить") { _, _ ->
                try {
                    val response = toolRegistry.execute(result)
                    if (response == null) respond("Инструмент не подключён.")
                    else respond(response)
                } catch (_: Throwable) {
                    respond("Android не смог выполнить эту команду.")
                }
            }
            .show()
    }

    private fun updateModelStatus() {
        val native = (intentEngine as? NeedleEngine)?.isNativeReady() == true
        val modelText = if (native) {
            "Needle 3: native Cactus runtime активен. Confidence gate: 0.70. Fast paths: активны."
        } else {
            "Needle 3: native runtime недоступен. Работаю через deterministic fast paths + fallback."
        }
        findViewById<TextView>(R.id.modelStatus).text = modelText
        if (!status.text.toString().contains("слушаю")) {
            status.text = if (native) "MINI-UNA  •  local + Needle" else "MINI-UNA  •  local fast paths"
        }
    }

    private fun refreshNote() {
        val note = prefs.getString("last_note", null)
        findViewById<TextView>(R.id.noteText).text =
            if (note == null) "Пока пусто." else "Последняя заметка:\n\n" + note
    }

    private fun respond(text: String) {
        output.text = text
        mascot.blink()
        if (prefs.getBoolean("voice", true) && ::tts.isInitialized) {
            if (tts.isSpeaking) tts.stop()
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mini_una")
        }
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            tts.language = Locale("ru", "RU")
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        (intentEngine as? NeedleEngine)?.close()
        super.onDestroy()
    }
}
