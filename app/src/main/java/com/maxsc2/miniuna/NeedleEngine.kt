package com.maxsc2.miniuna

import android.content.Context
import android.util.Log
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
        private const val INIT_RETRY_COOLDOWN_MS = 60_000L
        private val ARG_REQUIRED = setOf(
            "OPEN_APP", "OPEN_SETTINGS", "OPEN_WEB",
            "CALCULATE", "SAVE_NOTE",
            "SET_TIMER_SECONDS", "SET_TIMER_MINUTES", "SET_TIMER_HOURS",
            "NOTIF_FOLLOW", "NOTIF_UNFOLLOW",
            "YOUTUBE_SEARCH", "TG_SHARE"
        )
    }

    private var model: Long = 0L
    @Volatile private var nativeReady = false
    @Volatile var lastError: String = "not started"
        private set
    private val initLock = Any()
    private var toolsJson: String? = null
    private val androidTools = AndroidTools(context)
    private val appCatalog = InstalledAppCatalog(context)
    private var cachedAppPrompt = ""
    private var cachedAppPromptAt = 0L

    override fun classify(text: String): IntentResult {
        // Small deterministic fast paths keep common device controls reliable.
        // Needle remains the general router for commands outside this set.
        fastPath(text)?.let { return it }
        return routeSingle(text)
    }

    fun classifyAll(text: String): List<IntentResult> {
        fastPath(text)?.let { return listOf(it) }

        splitCompound(text)?.let { parts ->
            val out = parts.flatMap { part ->
                fastPath(part)?.let { listOf(it) } ?: listOf(fallback.classify(part))
            }.filter { it.intent != "UNKNOWN" }
            if (out.isNotEmpty()) return out.take(3)
        }

        if (!isNativeReady()) return listOf(fallback.classify(text))
        val multi = routeMulti(text)
        if (multi.isNotEmpty()) return multi.take(4)
        return listOf(routeSingle(text))
    }

    private fun splitCompound(raw: String): List<String>? {
        val t = raw.trim()
        if (t.isBlank()) return null
        val seps = listOf("а затем", "и потом", "потом", "и")
        for (core in seps) {
            val parts = t.split(Regex("(?i)\\s+" + Regex.escape(core) + "\\s+"))
            if (parts.size in 2..3 && parts.all { it.isNotBlank() }) {
                return parts.map { it.trim() }
            }
        }
        return null
    }

    private fun screenPrompt(): String {
        return try {
            val screen = androidTools.screenContext(800)
            if (screen.isBlank()) "" else " Current screen text (may help resolve references like 'it', 'there'): " + screen
        } catch (_: Throwable) {
            ""
        }
    }

    private fun routeSingle(text: String): IntentResult {
        // Never init on the calling (UI) thread: a failing nativeInit blocks
        // for seconds and causes ANRs. Init happens only in warmupAsync.
        if (!isNativeReady()) return fallback.classify(text)

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
                        cachedAppPrompt + screenPrompt()
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
                lastError = "nativeComplete вернул rc=$rc"
                Log.w("MiniUNA-Needle", lastError)
                nativeReady = false
                return fallback.classify(text)
            }

            val end = buffer.indexOf(0)
            val json = String(
                if (end >= 0) buffer.copyOf(end) else buffer,
                StandardCharsets.UTF_8
            ).trim()

            parseNeedleResults(json, text).firstOrNull() ?: fallback.classify(text)
        } catch (_: Throwable) {
            fallback.classify(text)
        }
    }

    fun routeMulti(text: String): List<IntentResult> {
        if (!isNativeReady()) return emptyList()
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
                        "If the user asks for several actions, return one function call per action, in order. " +
                        cachedAppPrompt + screenPrompt()
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
                lastError = "nativeComplete вернул rc=$rc"
                Log.w("MiniUNA-Needle", lastError)
                nativeReady = false
                return emptyList()
            }

            val end = buffer.indexOf(0)
            val json = String(
                if (end >= 0) buffer.copyOf(end) else buffer,
                StandardCharsets.UTF_8
            ).trim()

            parseNeedleResults(json, text)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun isNativeReady(): Boolean = nativeReady && model != 0L

    fun nativeStatus(): String = if (isNativeReady()) {
        "Needle 3: native Cactus runtime активен."
    } else {
        "Needle 3: недоступен ($lastError)."
    }

    fun warmupAsync(onDone: ((Boolean) -> Unit)? = null) {
        Thread {
            var delayMs = 10_000L
            var lastOk: Boolean? = null
            while (true) {
                val ok = try {
                    ensureNativeModel(force = true)
                } catch (e: Throwable) {
                    lastError = "warmup: ${e.message}"
                    Log.w("MiniUNA-Needle", lastError)
                    false
                }
                Log.i("MiniUNA-Needle", "warmup ok=$ok status=$lastError")
                if (ok) {
                    try {
                        onDone?.invoke(true)
                    } catch (_: Throwable) {
                    }
                    return@Thread
                }
                if (lastOk != false) {
                    try {
                        onDone?.invoke(false)
                    } catch (_: Throwable) {
                    }
                }
                lastOk = false
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                delayMs = (delayMs * 2).coerceAtMost(300_000L)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun fastPath(raw: String): IntentResult? {
        val t = raw.trim().lowercase(Locale.getDefault()).replace(Regex("""\\s+"""), " ")
        if (t.isBlank()) return null

        // Music requests go before the generic app-launch path:
        // "включи музыку" must start playback, not just open an app page.
        if (
            t.contains("включи музыку") ||
            t.contains("включи музычку") ||
            t.contains("поставь музыку") ||
            t == "музыка" ||
            t == "музыку"
        ) {
            return IntentResult("PLAY_MUSIC", 0.995f, reasoning = "deterministic music fast path")
        }

        if (
            t.contains("следующий трек") ||
            t.contains("следующая песня") ||
            t.contains("включи следующую")
        ) {
            return IntentResult("MEDIA_NEXT", 0.995f, reasoning = "deterministic music fast path")
        }

        if (
            t.contains("предыдущий трек") ||
            t.contains("предыдущая песня")
        ) {
            return IntentResult("MEDIA_PREV", 0.995f, reasoning = "deterministic music fast path")
        }

        if (
            t == "пауза" ||
            t.contains("на паузу") ||
            t.contains("с паузы") ||
            t == "продолжи" ||
            t == "играй"
        ) {
            return IntentResult("MEDIA_TOGGLE", 0.995f, reasoning = "deterministic music fast path")
        }

        if (
            t.contains("что на экране") ||
            t.contains("прочитай экран") ||
            t.contains("что там на экране") ||
            t == "экран"
        ) {
            return IntentResult("SCREEN_READ", 0.995f, reasoning = "deterministic screen fast path")
        }

        if (
            t.contains("прочитай уведомления") ||
            t.contains("покажи уведомления") ||
            t.contains("что в уведомлениях") ||
            t == "уведомления"
        ) {
            return IntentResult("NOTIF_READ", 0.995f, reasoning = "deterministic notifications fast path")
        }

        val followMatch = Regex(".*следи за\\s+(.+?)\\s*[.!?]?$").find(t)
        if (followMatch != null && !t.contains("не следи")) {
            val app = followMatch.groupValues[1].trim()
            if (app.isNotEmpty()) {
                return IntentResult("NOTIF_FOLLOW", 0.99f, mapOf("app" to app), reasoning = "deterministic notifications fast path")
            }
        }

        val unfollowMatch = Regex(".*не следи за\\s+(.+?)\\s*[.!?]?$").find(t)
        if (unfollowMatch != null) {
            val app = unfollowMatch.groupValues[1].trim()
            if (app.isNotEmpty()) {
                return IntentResult("NOTIF_UNFOLLOW", 0.99f, mapOf("app" to app), reasoning = "deterministic notifications fast path")
            }
        }

        if (
            t.contains("на ютубе") ||
            t.contains("на ютьюбе") ||
            t.contains("в ютубе")
        ) {
            return IntentResult("YOUTUBE_SEARCH", 0.99f, mapOf("query" to raw.trim()), reasoning = "deterministic youtube fast path")
        }

        val tgMatch = Regex(".*(?:отправь|напиши)(?:.*?(?:в телеграм|в телегу|телеграм|телегу))?\\s+(.+?)\\s*[.!?]?$").find(t)
        if (tgMatch != null && (t.contains("телеграм") || t.contains("телегу") || t.contains("отправь"))) {
            val text = tgMatch.groupValues[1].trim()
            if (text.isNotEmpty() && !text.equals("телеграм", true) && !text.equals("телегу", true)) {
                return IntentResult("TG_SHARE", 0.97f, mapOf("text" to text), reasoning = "deterministic telegram fast path")
            }
        }

        // Explicit app launch commands. "найди Chrome" stays a web-search request;
        // "открой Chrome" is an app-launch request.
        val appMatch = Regex(
            "^(?:открой|запусти|запуск|включи|перейди в|зайди в)\\s+(.+?)\\s*[.!?]?$"
        ).find(t)

        if (appMatch != null) {
            val app = appMatch.groupValues[1].trim()
            val resolved = androidTools.resolveInstalledApp(app)
            if (resolved != null) {
                return IntentResult(
                    intent = "OPEN_APP",
                    confidence = 0.995f,
                    arguments = mapOf("app" to resolved.label),
                    reasoning = "installed-app catalog fast path"
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

    private fun parseNeedleResults(json: String, originalText: String): List<IntentResult> {
        val root = try {
            JSONObject(json)
        } catch (_: Throwable) {
            return emptyList()
        }

        val confidence = root.optDouble("confidence", 0.0).toFloat()
        val reasoning = root.optString("reasoning", "")
        val calls = root.optJSONArray("function_calls")
        val suppressed = root.optJSONArray("suppressed_calls")

        if (calls != null && calls.length() > 0) {
            val out = mutableListOf<IntentResult>()
            for (i in 0 until minOf(calls.length(), 4)) {
                val result = parseCall(calls.getJSONObject(i), confidence, reasoning)
                if (result.intent == "UNKNOWN") continue
                if (result.arguments.isEmpty() && result.intent in ARG_REQUIRED) continue
                out.add(result.copy(requiresConfirmation = confidence < CONFIDENCE_THRESHOLD))
            }
            if (out.isNotEmpty()) return out
            return listOf(fallback.classify(originalText))
        }

        if (suppressed != null && suppressed.length() > 0) {
            val result = parseCall(suppressed.getJSONObject(0), confidence, reasoning)
            return if (result.intent == "UNKNOWN") {
                listOf(IntentResult("UNKNOWN", confidence, reasoning = reasoning))
            } else {
                listOf(result.copy(requiresConfirmation = true))
            }
        }

        return listOf(IntentResult("UNKNOWN", confidence, reasoning = reasoning))
    }

    // Legacy single-result entry, kept for direct callers.
    private fun parseNeedleResult(json: String, originalText: String): IntentResult? =
        parseNeedleResults(json, originalText).firstOrNull {
            it.intent != "UNKNOWN"
        } ?: fallback.classify(originalText)


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
            "play_music" -> "PLAY_MUSIC"
            "media_play_pause" -> "MEDIA_TOGGLE"
            "media_next" -> "MEDIA_NEXT"
            "media_previous" -> "MEDIA_PREV"
            "screen_read" -> "SCREEN_READ"
            "read_notifications" -> "NOTIF_READ"
            "follow_notifications" -> "NOTIF_FOLLOW"
            "unfollow_notifications" -> "NOTIF_UNFOLLOW"
            "youtube_search" -> "YOUTUBE_SEARCH"
            "send_telegram" -> "TG_SHARE"
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

    private fun ensureNativeModel(force: Boolean = false): Boolean {
        if (nativeReady && model != 0L) return true

        // Don't hammer a failing init on the UI thread: at most one attempt
        // per cooldown window. Background warmup bypasses with force=true.
        if (!force) {
            val now = System.currentTimeMillis()
            if (now - lastInitAttemptAt < INIT_RETRY_COOLDOWN_MS) return false
            lastInitAttemptAt = now
        }

        synchronized(initLock) {
            if (nativeReady && model != 0L) return true
            return try {
                try {
                    if (toolsJson == null) {
                        toolsJson = context.assets.open("needle_tools.json")
                            .bufferedReader()
                            .use { it.readText() }
                    }
                } catch (e: Throwable) {
                    lastError = "нет needle_tools.json в assets: ${e.message}"
                    Log.w("MiniUNA-Needle", lastError)
                    return false
                }

                val modelFile = File(context.filesDir, "needle3.cact")
                if (!copyModel(modelFile)) return false
                if (tryInit(modelFile)) return true

                // One retry with a fresh copy: the first copy may be truncated
                // (e.g. process died mid-copy), and a partial file always fails init.
                if (!initRetried) {
                    initRetried = true
                    Log.w("MiniUNA-Needle", "init failed, retrying with fresh copy")
                    try {
                        modelFile.delete()
                    } catch (_: Throwable) {
                    }
                    if (!copyModel(modelFile)) return false
                    if (tryInit(modelFile)) return true
                }
                nativeReady = false
                false
            } catch (e: Throwable) {
                lastError = "ensure: ${e.message}"
                Log.w("MiniUNA-Needle", lastError)
                nativeReady = false
                false
            }
        }
    }

    @Volatile private var initRetried = false
    @Volatile private var lastInitAttemptAt = 0L

    private fun copyModel(modelFile: File): Boolean {
        if (modelFile.exists() && modelFile.length() > 0L) return true
        return try {
            context.assets.open("needle3.cact").use { input ->
                FileOutputStream(modelFile).use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Throwable) {
            lastError = "нет needle3.cact в APK (assets): ${e.message}"
            Log.w("MiniUNA-Needle", lastError)
            false
        }
    }

    private fun tryInit(modelFile: File): Boolean {
        val sizeMb = modelFile.length() / 1048576.0
        try {
            model = CactusJNI.nativeInit(modelFile.absolutePath, null, false)
        } catch (e: UnsatisfiedLinkError) {
            lastError = "нет libcactus_engine.so для этого ABI: ${e.message}"
            Log.w("MiniUNA-Needle", lastError)
            nativeReady = false
            return false
        } catch (e: Throwable) {
            lastError = "nativeInit упал: ${e.message}"
            Log.w("MiniUNA-Needle", lastError)
            nativeReady = false
            return false
        }
        if (model == 0L) {
            val detail = try {
                CactusJNI.nativeGetLastError()
            } catch (_: Throwable) {
                ""
            }
            val size = "%.1f".format(Locale.US, sizeMb)
            lastError = "nativeInit вернул 0 (файл $size МБ)" +
                if (detail.isNullOrBlank()) " (память или файл модели?)" else ": $detail"
            Log.w("MiniUNA-Needle", lastError)
            nativeReady = false
            return false
        }
        nativeReady = true
        lastError = "ok"
        Log.i("MiniUNA-Needle", "native model ready")
        return true
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
