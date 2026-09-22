package com.maxsc2.miniuna

import android.content.Context
import com.cactus.CactusJNI
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

class NeedleEngine(
    private val context: Context,
    private val fallback: IntentEngine = LocalIntentEngine()
) : IntentEngine {

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.70f
        private const val BUFFER_SIZE = 1024 * 1024
    }

    private var model: Long = 0L
    private var nativeReady = false
    private var toolsJson: String? = null
    private val appCatalog = InstalledAppCatalog(context)
    private var cachedAppPrompt = ""
    private var cachedAppPromptAt = 0L

    override fun classify(text: String): IntentResult {
        // Small deterministic fast paths keep common device controls reliable.
        // Needle remains the general router for commands outside this set.
        fastPath(text)?.let { return it }

        if (!ensureNativeModel()) return fallback.classify(text)

        return try {
            val now = System.currentTimeMillis()
            if (now - cachedAppPromptAt > 60_000L || cachedAppPrompt.isBlank()) {
                cachedAppPrompt = appCatalog.promptCatalog()
                cachedAppPromptAt = now
            }

            val system = JSONObject()
                .put("role", "system")
                .put(
                    "content",
                    "Device: Android phone. Use only declared tools. Never invent missing arguments. " +
                        "For open_app, map the user's spoken name to one installed app from this list. " +
                        "Examples: «ютуб» means YouTube, «хром» means Chrome. " +
                        cachedAppPrompt
                )
            val user = JSONObject()
                .put("role", "user")
                .put("content", text)
            val messages = "[" + system + "," + user + "]"

            val buffer = ByteArray(BUFFER_SIZE)
            val rc = CactusJNI.nativeComplete(
                model,
                messages,
                buffer,
                null,
                toolsJson,
                null,
                null
            )

            if (rc < 0) {
                nativeReady = false
                return fallback.classify(text)
            }

            val end = buffer.indexOf(0)
            val json = String(
                if (end >= 0) buffer.copyOf(end) else buffer,
                StandardCharsets.UTF_8
            ).trim()

            parseNeedleResult(json, text) ?: fallback.classify(text)
        } catch (_: Throwable) {
            fallback.classify(text)
        }
    }

    fun isNativeReady(): Boolean = nativeReady && model != 0L

    private fun fastPath(raw: String): IntentResult? {
        val t = raw.trim().lowercase(Locale.getDefault()).replace(Regex("""\\s+"""), " ")
        if (t.isBlank()) return null

        // Explicit app launch commands. "найди Chrome" stays a web-search request;
        // "открой Chrome" is an app-launch request.
        val appMatch = Regex(
            "^(?:открой|запусти|запуск|включи|перейди в|зайди в)\\s+(.+?)\\s*[.!?]?$"
        ).find(t)

        if (appMatch != null) {
            val app = appMatch.groupValues[1].trim()
            val known = setOf(
                "chrome", "хром", "google chrome",
                "youtube", "ютуб",
                "telegram", "телеграм",
                "whatsapp", "ватсап",
                "spotify", "спотифай",
                "калькулятор"
            )
            if (app in known) {
                return IntentResult(
                    intent = "OPEN_APP",
                    confidence = 0.995f,
                    arguments = mapOf("app" to app),
                    reasoning = "deterministic app-launch fast path"
                )
            }
        }

        // Common Russian voice forms for volume.
        if (
            t.contains("сделай громче") ||
            t.contains("увеличь громкость") ||
            t.contains("прибавь громкость") ||
            t == "громче" ||
            t == "громкость вверх"
        ) {
            return IntentResult("VOLUME_UP", 0.995f, reasoning = "deterministic volume fast path")
        }

        if (
            t.contains("сделай тише") ||
            t.contains("уменьши громкость") ||
            t.contains("убавь громкость") ||
            t == "тише" ||
            t == "громкость вниз"
        ) {
            return IntentResult("VOLUME_DOWN", 0.995f, reasoning = "deterministic volume fast path")
        }

        if (
            t.contains("без звука") ||
            t.contains("выключи звук") ||
            t.contains("включи звук") ||
            t == "мьют"
        ) {
            return IntentResult("VOLUME_MUTE", 0.995f, reasoning = "deterministic volume fast path")
        }

        val timerMatch = Regex(
            ".*(?:таймер|таймерчик|минутник).*?(\\d+)\\s*(секунд(?:а|ы)?|сек|с|минут(?:а|ы)?|мин|час(?:а|ов)?|ч).*"
        ).find(t)

        if (timerMatch != null) {
            val value = timerMatch.groupValues[1].toIntOrNull() ?: return null
            return when (timerMatch.groupValues[2]) {
                "с", "сек", "секунда", "секунды", "секунд" ->
                    IntentResult(
                        "SET_TIMER_SECONDS",
                        0.995f,
                        mapOf("seconds" to value.toString()),
                        reasoning = "deterministic timer fast path"
                    )

                "ч", "час", "часа", "часов" ->
                    IntentResult(
                        "SET_TIMER_HOURS",
                        0.995f,
                        mapOf("hours" to value.toString()),
                        reasoning = "deterministic timer fast path"
                    )

                else ->
                    IntentResult(
                        "SET_TIMER_MINUTES",
                        0.995f,
                        mapOf("minutes" to value.toString()),
                        reasoning = "deterministic timer fast path"
                    )
            }
        }

        val timerWordMatch = Regex(
            ".*(?:таймер|таймерчик|минутник).*?([а-я]+)\\s*(минут(?:а|ы)?|мин|секунд(?:а|ы)?|сек|час(?:а|ов)?|ч).*"
        ).find(t)

        if (timerWordMatch != null) {
            val value = russianNumber(timerWordMatch.groupValues[1]) ?: return null
            return when (timerWordMatch.groupValues[2]) {
                "сек", "секунда", "секунды", "секунд" ->
                    IntentResult(
                        "SET_TIMER_SECONDS",
                        0.99f,
                        mapOf("seconds" to value.toString()),
                        reasoning = "deterministic Russian-number timer fast path"
                    )

                "ч", "час", "часа", "часов" ->
                    IntentResult(
                        "SET_TIMER_HOURS",
                        0.99f,
                        mapOf("hours" to value.toString()),
                        reasoning = "deterministic Russian-number timer fast path"
                    )

                else ->
                    IntentResult(
                        "SET_TIMER_MINUTES",
                        0.99f,
                        mapOf("minutes" to value.toString()),
                        reasoning = "deterministic Russian-number timer fast path"
                    )
            }
        }

        return null
    }

    private fun russianNumber(value: String): Int? = mapOf(
        "ноль" to 0,
        "один" to 1, "одна" to 1,
        "два" to 2, "две" to 2,
        "три" to 3,
        "четыре" to 4,
        "пять" to 5,
        "шесть" to 6,
        "семь" to 7,
        "восемь" to 8,
        "девять" to 9,
        "десять" to 10,
        "одиннадцать" to 11,
        "двенадцать" to 12,
        "тринадцать" to 13,
        "четырнадцать" to 14,
        "пятнадцать" to 15,
        "двадцать" to 20,
        "тридцать" to 30,
        "сорок" to 40,
        "пятьдесят" to 50,
        "шестьдесят" to 60
    )[value]

    private fun parseNeedleResult(json: String, originalText: String): IntentResult? {
        val root = try {
            JSONObject(json)
        } catch (_: Throwable) {
            return null
        }

        val confidence = root.optDouble("confidence", 0.0).toFloat()
        val reasoning = root.optString("reasoning", "")
        val calls = root.optJSONArray("function_calls")
        val suppressed = root.optJSONArray("suppressed_calls")

        if (calls != null && calls.length() > 0) {
            val result = parseCall(calls.getJSONObject(0), confidence, reasoning)
            if (result.intent == "UNKNOWN") return fallback.classify(originalText)
            if (result.arguments.isEmpty() && result.intent in setOf(
                    "OPEN_APP", "OPEN_SETTINGS", "OPEN_WEB",
                    "CALCULATE", "SAVE_NOTE",
                    "SET_TIMER_SECONDS", "SET_TIMER_MINUTES", "SET_TIMER_HOURS"
                )
            ) {
                return fallback.classify(originalText)
            }
            return result.copy(requiresConfirmation = confidence < CONFIDENCE_THRESHOLD)
        }

        if (suppressed != null && suppressed.length() > 0) {
            val result = parseCall(suppressed.getJSONObject(0), confidence, reasoning)
            return if (result.intent == "UNKNOWN") {
                IntentResult("UNKNOWN", confidence, reasoning = reasoning)
            } else {
                result.copy(requiresConfirmation = true)
            }
        }

        return IntentResult("UNKNOWN", confidence, reasoning = reasoning)
    }


    private fun parseCall(
        call: JSONObject,
        confidence: Float,
        reasoning: String
    ): IntentResult {
        val name = call.optString("name")
        val args = call.optJSONObject("arguments")
        val map = mutableMapOf<String, String>()

        if (args != null) {
            val keys = args.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = args.opt(key)
                if (value != JSONObject.NULL) map[key] = value.toString()
            }
        }

        val intent = when (name) {
            "get_time" -> "GET_TIME"
            "get_date" -> "GET_DATE"
            "open_app" -> "OPEN_APP"
            "open_settings" -> "OPEN_SETTINGS"
            "open_web" -> "OPEN_WEB"
            "calculate" -> "CALCULATE"
            "save_note" -> "SAVE_NOTE"
            "set_timer_seconds" -> "SET_TIMER_SECONDS"
            "set_timer_minutes" -> "SET_TIMER_MINUTES"
            "set_timer_hours" -> "SET_TIMER_HOURS"
            "volume_up" -> "VOLUME_UP"
            "volume_down" -> "VOLUME_DOWN"
            "volume_mute" -> "VOLUME_MUTE"
            "go_back" -> "BACK"
            "go_home" -> "HOME"
            "open_recents" -> "RECENTS"
            "lock_screen" -> "LOCK_SCREEN"
            "open_accessibility_settings" -> "OPEN_ACCESSIBILITY_SETTINGS"
            else -> "UNKNOWN"
        }

        return IntentResult(
            intent = intent,
            confidence = confidence,
            arguments = map,
            reasoning = reasoning
        )
    }

    private fun ensureNativeModel(): Boolean {
        if (nativeReady && model != 0L) return true

        return try {
            if (toolsJson == null) {
                toolsJson = context.assets.open("needle_tools.json")
                    .bufferedReader()
                    .use { it.readText() }
            }

            val modelFile = File(context.filesDir, "needle3.cact")
            if (!modelFile.exists() || modelFile.length() == 0L) {
                context.assets.open("needle3.cact").use { input ->
                    FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                }
            }

            model = CactusJNI.nativeInit(modelFile.absolutePath, null, false)
            nativeReady = model != 0L
            nativeReady
        } catch (_: Throwable) {
            nativeReady = false
            false
        }
    }

    fun close() {
        if (model != 0L) {
            try {
                CactusJNI.nativeDestroy(model)
            } catch (_: Throwable) {
            }
        }
        model = 0L
        nativeReady = false
    }
}
