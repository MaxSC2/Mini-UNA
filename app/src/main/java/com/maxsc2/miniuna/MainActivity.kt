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
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var mascot: MascotView
    private lateinit var mascotPreview: MascotView
    private lateinit var tts: TextToSpeech
    private lateinit var intentEngine: IntentEngine
    private lateinit var toolRegistry: ToolRegistry

    private val voiceRequest = 700
    private val micPermission = 701
    private val prefs by lazy { getSharedPreferences("mini_una", MODE_PRIVATE) }
    private var pending: PendingSlot? = null
    private var autoListenArmed = false

    // Разбор команды может уйти в Cactus nativeComplete (секунды) и в обход дерева
    // доступности, поэтому он живёт в одном фоновом потоке, а не на UI-потоке.
    private val background: ExecutorService = Executors.newSingleThreadExecutor()

    private val pages by lazy {
        listOf(
            findViewById<View>(R.id.homePage),
            findViewById<View>(R.id.toolsPage),
            findViewById<View>(R.id.notesPage),
            findViewById<View>(R.id.mascotPage),
            findViewById<View>(R.id.settingsPage)
        )
    }

    private fun eachMascot(block: (MascotView) -> Unit) {
        block(mascot)
        block(mascotPreview)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        output = findViewById(R.id.output)
        status = findViewById(R.id.status)
        mascot = findViewById(R.id.mascot)
        mascotPreview = findViewById(R.id.mascotPreview)
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "mini_una_q" && autoListenArmed) {
                    autoListenArmed = false
                    runOnUiThread { listen() }
                }
            }
        })

        intentEngine = NeedleEngine(this, LocalIntentEngine())
        toolRegistry = ToolRegistry(this) { note ->
            prefs.edit().putString("last_note", note).apply()
            refreshNote()
        }

        setupNavigation()
        setupSettings()
        refreshNote()
        applySavedMascotState()
        setupMascotControls()
        refreshAppCatalogStatus()
        updateModelStatus()
        (intentEngine as? NeedleEngine)?.warmupAsync {
            runOnUiThread { updateModelStatus() }
        }
        showPage(0)
        intent.getStringExtra(HotwordService.EXTRA_COMMAND)?.takeIf { it.isNotBlank() }?.let { handle(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(HotwordService.EXTRA_COMMAND)?.takeIf { it.isNotBlank() }?.let { handle(it) }
    }

    private fun setupNavigation() {
        findViewById<Button>(R.id.navHome).setOnClickListener { showPage(0) }
        findViewById<Button>(R.id.navTools).setOnClickListener { showPage(1) }
        findViewById<Button>(R.id.navNotes).setOnClickListener { showPage(2) }
        findViewById<Button>(R.id.navMascot).setOnClickListener { showPage(3) }
        findViewById<Button>(R.id.navSettings).setOnClickListener { showPage(4) }
        findViewById<Button>(R.id.mic).setOnClickListener { listen() }

        findViewById<Button>(R.id.quickTime).setOnClickListener {
            handle("который сейчас час")
        }
        findViewById<Button>(R.id.quickApps).setOnClickListener {
            refreshAppSummary()
            showPage(1)
        }
        findViewById<Button>(R.id.quickNote).setOnClickListener {
            showNoteComposer()
        }
        findViewById<Button>(R.id.quickYoutube).setOnClickListener {
            handle("открой YouTube")
        }
        findViewById<Button>(R.id.quickTimer).setOnClickListener {
            handle("поставь таймер на 5 минут")
        }
        findViewById<Button>(R.id.refreshApps).setOnClickListener {
            refreshAppSummary()
            respond("Список приложений обновлён.")
        }

        findViewById<Button>(R.id.testTool).setOnClickListener {
            // classify() может уйти в CactusJNI.nativeComplete — держим это вне UI-потока.
            background.execute {
                val sample = "который сейчас час"
                val result = intentEngine.classify(sample)
                val native = (intentEngine as? NeedleEngine)?.isNativeReady() == true
                val text =
                    "Router test: " + result.intent + " (" +
                        (result.confidence * 100).roundToInt() + "%)\n\n" +
                        "Needle runtime: " + (if (native) "native" else "fallback") +
                        "\n\nПопробуй голосом: «открой Telegram», «поставь таймер на 5 минут», «громче», «назад»."
                runOnUiThread { findViewById<TextView>(R.id.toolStatus).text = text }
            }
        }

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.notifButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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
        val navs = listOf(R.id.navHome, R.id.navTools, R.id.navNotes, R.id.navMascot, R.id.navSettings)
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
            eachMascot { it.setTracking(checked) }
        }
        breathing.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("breathing", checked).apply()
            eachMascot { it.setBreathing(checked) }
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
                    eachMascot { it.setEmotion(pair.first) }
                    for (i in 0 until emotionBox.childCount) {
                        emotionBox.getChildAt(i).alpha = 0.55f
                    }
                    alpha = 1f
                    eachMascot { it.blink() }
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

    private fun setupMascotControls() {
        val aura = findViewById<SwitchCompat>(R.id.toggleAura)
        val light = findViewById<SwitchCompat>(R.id.toggleLight)
        val gloss = findViewById<SwitchCompat>(R.id.toggleGloss)
        val look = findViewById<SeekBar>(R.id.seekLook)
        val eyes = findViewById<SeekBar>(R.id.seekEyes)
        val roll = findViewById<SeekBar>(R.id.seekRoll)

        aura.isChecked = prefs.getBoolean("aura", true)
        light.isChecked = prefs.getBoolean("light", true)
        gloss.isChecked = prefs.getBoolean("gloss", true)

        look.progress = prefs.getInt("look", 55)
        eyes.progress = prefs.getInt("eyes", 50)
        roll.progress = prefs.getInt("roll", 50)

        aura.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("aura", checked).apply()
            eachMascot { it.setAuraEnabled(checked) }
        }
        light.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("light", checked).apply()
            eachMascot { it.setLightAnimation(checked) }
        }
        gloss.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("gloss", checked).apply()
            eachMascot { it.setGloss(checked) }
        }

        look.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            prefs.edit().putInt("look", value).apply()
            eachMascot { it.setLookTravel(0.08f + value / 100f * 0.26f) }
        })
        eyes.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            prefs.edit().putInt("eyes", value).apply()
            eachMascot { it.setEyeScale(0.80f + value / 100f * 0.55f) }
        })
        roll.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            prefs.edit().putInt("roll", value).apply()
            eachMascot { it.setRoll(value / 100f * 20f) }
        })
    }

    private fun simpleSeekBarListener(onProgress: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onProgress(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun refreshAppCatalogStatus() {
        Thread {
            val count = try {
                AndroidTools(this).appCount()
            } catch (_: Throwable) {
                -1
            }
            runOnUiThread {
                if (count < 0) return@runOnUiThread
                findViewById<TextView>(R.id.appCatalogStatus).text =
                    "Вижу " + count + " запускаемых приложений на устройстве. " +
                        "Needle получает их названия и может сопоставлять разговорные варианты вроде «ютуб» → YouTube."
            }
        }.apply { isDaemon = true; start() }
    }

    private fun applySavedMascotState() {
        eachMascot { m ->
            m.setTracking(prefs.getBoolean("tracking", true))
            m.setBreathing(prefs.getBoolean("breathing", true))
            m.setAuraEnabled(prefs.getBoolean("aura", true))
            m.setLightAnimation(prefs.getBoolean("light", true))
            m.setGloss(prefs.getBoolean("gloss", true))
            m.setEmotion(prefs.getString("emotion", "neutral") ?: "neutral")
        }
        applyPalette(
            paletteByName(prefs.getString("palette", "Синяя сфера"))
                ?: paletteByName("Синяя сфера")!!
        )
        refreshAppSummary()
    }

    private fun refreshAppSummary() {
        Thread {
            val text = try {
                InstalledAppCatalog(this).summary()
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (text != null) findViewById<TextView>(R.id.appSummary).text = text
            }
        }.apply { isDaemon = true; start() }
    }

    private fun showNoteComposer() {
        val input = EditText(this).apply {
            hint = "Например: купить молоко"
            setSingleLine(false)
            minLines = 2
            maxLines = 5
        }

        AlertDialog.Builder(this)
            .setTitle("Новая заметка")
            .setMessage("Я сохраню её только на этом телефоне.")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val note = input.text.toString().trim()
                if (note.isNotBlank()) {
                    prefs.edit().putString("last_note", note).apply()
                    refreshNote()
                    respond("Записала. Заметка сохранена на устройстве.")
                    showPage(2)
                }
            }
            .show()
    }

    private fun applyPalette(palette: Palette) {
        eachMascot {
            it.setPalette(
                Color.parseColor(palette.core),
                Color.parseColor(palette.mid),
                Color.parseColor(palette.rim),
                Color.parseColor(palette.aura)
            )
        }
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

        val input = text.trim()
        if (pending != null) onSlotAnswer(input) else handle(input)
    }

    private fun handle(raw: String) {
        status.text = "MINI-UNA  •  думаю…"
        background.execute {
            val engine = intentEngine as? NeedleEngine
            val results = try {
                engine?.classifyAll(raw) ?: listOf(intentEngine.classify(raw))
            } catch (_: Throwable) {
                listOf(IntentResult("UNKNOWN", 0f))
            }
            runOnUiThread { applyResults(results) }
        }
    }

    // Выполнение действий идёт на UI-потоке: тут стартуют Activity и диалоги.
    private fun applyResults(results: List<IntentResult>) {
        if (results.size <= 1) {
            val r = results.firstOrNull() ?: IntentResult("UNKNOWN", 0f)
            val missing = SlotHelper.missing(r.intent, r.arguments)
            if (missing.isNotEmpty()) {
                pending = PendingSlot(r.intent, r.arguments.toMutableMap(), missing)
                askSlot()
                updateModelStatus()
                return
            }
            handleSingle(r)
            updateModelStatus()
            return
        }

        val allowed = results.filter { SafetyPolicy.decide(it) == SafetyPolicy.Decision.ALLOW }
        val rest = results - allowed.toSet()
        if (rest.isNotEmpty()) {
            showMultiConfirmation(allowed, rest)
            updateModelStatus()
            return
        }

        val responses = allowed.map { executeOrHelp(it) }
        respond(responses.joinToString(" "))
        updateModelStatus()
    }

    private fun showMultiConfirmation(allowed: List<IntentResult>, rest: List<IntentResult>) {
        val names = (allowed + rest).joinToString(", ") {
            it.intent.replace('_', ' ').lowercase(Locale.getDefault())
        }
        AlertDialog.Builder(this)
            .setTitle("Несколько действий")
            .setMessage(
                "Mini-UNA выполнит их по порядку:\n\n" + names +
                    "\n\nДействия вне списка безопасных требуют отдельного подтверждения — скажи их по одному."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выполнить") { _, _ ->
                val responses = allowed.map { executeOrHelp(it) }
                if (responses.isNotEmpty()) respond(responses.joinToString(" "))
                updateModelStatus()
            }
            .show()
    }

    private fun executeOrHelp(result: IntentResult): String {
        if (result.intent == "HELP") {
            return "Умею открывать приложения и настройки, искать в интернете и на YouTube, считать, ставить таймер и будильник, менять громкость, включать музыку, читать экран и уведомления, отправлять в Telegram и выполнять системные действия. А ещё со мной можно просто поболтать. Скажи «слушай Юну» — и я буду реагировать на имя даже со свёрнутым приложением."
        }
        return try {
            toolRegistry.execute(result)
                ?: "Инструмент для «" + result.intent + "» не подключён."
        } catch (_: Throwable) {
            "Android не смог выполнить эту команду."
        }
    }

    private fun handleSingle(result: IntentResult) {
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
                respond(executeOrHelp(result))
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
        val engine = intentEngine as? NeedleEngine
        val native = engine?.isNativeReady() == true
        val modelText = if (native) {
            "Needle 3: native Cactus runtime активен. Confidence gate: 0.70. Fast paths: активны."
        } else {
            (engine?.nativeStatus() ?: "Needle 3: движок недоступен.") +
                " Работаю через deterministic fast paths + fallback."
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

    private fun askSlot() {
        val p = pending ?: return
        val question = SlotHelper.question(p.missing.firstOrNull() ?: return)
        if (prefs.getBoolean("voice", true)) {
            respond(question, autoListen = true)
        } else {
            showSlotDialog(question)
        }
    }

    private fun showSlotDialog(question: String) {
        val input = EditText(this).apply {
            hint = question
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(question)
            .setView(input)
            .setNegativeButton("Отмена") { _, _ ->
                pending = null
                respond("Ладно, отменила.")
            }
            .setPositiveButton("OK") { _, _ ->
                onSlotAnswer(input.text.toString().trim())
            }
            .show()
    }

    private fun onSlotAnswer(answer: String) {
        val p = pending
        if (p == null) {
            handle(answer)
            return
        }
        if (answer.isBlank()) {
            askSlot()
            return
        }
        if (SlotHelper.isCancel(answer)) {
            pending = null
            respond("Ладно, отменила.")
            updateModelStatus()
            return
        }
        if (SlotHelper.fill(p, answer) && p.missing.isNotEmpty()) {
            p.retries = 0
            askSlot()
            updateModelStatus()
            return
        }
        if (p.missing.isEmpty()) {
            pending = null
            handleSingle(IntentResult(p.intent, 0.95f, p.args.toMap()))
            updateModelStatus()
            return
        }
        p.retries++
        if (p.retries >= 1) {
            pending = null
            handle(answer)
        } else {
            askSlot()
        }
        updateModelStatus()
    }

    private fun respond(text: String, autoListen: Boolean = false) {
        output.text = text
        mascot.blink()
        if (prefs.getBoolean("voice", true) && ::tts.isInitialized) {
            if (tts.isSpeaking) tts.stop()
            autoListenArmed = autoListen
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, if (autoListen) "mini_una_q" else "mini_una")
        } else {
            autoListenArmed = false
        }
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            tts.language = Locale("ru", "RU")
        }
    }

    override fun onDestroy() {
        background.shutdownNow()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        (intentEngine as? NeedleEngine)?.close()
        super.onDestroy()
    }
}
