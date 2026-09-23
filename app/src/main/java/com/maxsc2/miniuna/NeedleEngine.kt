package com.maxsc2.miniuna

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.Locale

class NeedleEngine(
    private val context: Context,
    private val fallback: IntentEngine = LocalIntentEngine()
) : IntentEngine {

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.70f
        // Ниже пола — не подтверждение, а «не понял»: рандомные вызовы с
        // мизерной уверенностью отбрасываются вместо диалога.
        private const val CONFIDENCE_FLOOR = 0.25f
        private val ARG_REQUIRED = setOf(
            "OPEN_APP", "OPEN_SETTINGS", "OPEN_WEB",
            "CALCULATE", "SAVE_NOTE",
            "SET_TIMER_SECONDS", "SET_TIMER_MINUTES", "SET_TIMER_HOURS",
            "NOTIF_FOLLOW", "NOTIF_UNFOLLOW",
            "YOUTUBE_SEARCH", "TG_SHARE", "REMIND", "SCREEN_TAP",
            "SHOP_ADD", "SHOP_REMOVE"
        )
    }

    @Volatile private var nativeReady = false
    @Volatile var lastError: String = "not started"
        private set
    // Бинаря needle нет в APK или процесс не стартует вовсе: повторные
    // попытки бессмысленны, помечаем как неустранимое и останавливаем ретраи.
    @Volatile private var serverFatal = false
    @Volatile private var serverFatalReason: String = ""
    private val initLock = Any()
    private val server = NeedleServer(context)
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
        // Составную команду разбираем до одиночных fast path: иначе
        // «поставь таймер на 5 минут и включи музыку» целиком матчится
        // таймерной регуляркой и второе действие молча теряется.
        splitCompound(text)?.let { parts ->
            val out = parts.map { part ->
                fastPath(part) ?: fallback.classify(part)
            }.filter { it.intent != "UNKNOWN" }
            if (out.size >= 2) return out.take(3)
        }

        fastPath(text)?.let { return listOf(it) }

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
            val maxChars = try {
                context.getSharedPreferences("mini_una", Context.MODE_PRIVATE)
                    .getInt("needle_screen", 500).coerceIn(0, 1500)
            } catch (_: Throwable) {
                500
            }
            if (maxChars <= 0) return ""
            val screen = androidTools.screenContext(maxChars)
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

            val input =
                "Device: Android phone. Use only declared tools. Never invent missing arguments. " +
                    "For open_app, map the user's spoken name to one installed app from this list. " +
                    "Examples: «ютуб» means YouTube, «хром» means Chrome. " +
                    cachedAppPrompt + screenPrompt() +
                    "\n\nUser request: " + text

            val json = server.complete(input)
            if (json == null) {
                lastError = server.lastError
                nativeReady = false
                return fallback.classify(text)
            }

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

            val input =
                "Device: Android phone. Use only declared tools. Never invent missing arguments. " +
                    "If the user asks for several actions, return one function call per action, in order. " +
                    cachedAppPrompt + screenPrompt() +
                    "\n\nUser request: " + text

            val json = server.complete(input)
            if (json == null) {
                lastError = server.lastError
                nativeReady = false
                return emptyList()
            }

            parseNeedleResults(json, text)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun confThreshold(): Float {
        return try {
            context.getSharedPreferences("mini_una", Context.MODE_PRIVATE)
                .getInt("needle_conf", (CONFIDENCE_THRESHOLD * 100).toInt()) / 100f
        } catch (_: Throwable) {
            CONFIDENCE_THRESHOLD
        }
    }

    fun isNativeReady(): Boolean = nativeReady

    fun serverLastMs(): Long = server.lastMs

    fun nativeStatus(): String = when {
        isNativeReady() -> "Needle 3: локальный сервер активен."
        serverFatal -> "Needle 3: недоступен — $serverFatalReason"
        else -> "Needle 3: недоступен ($lastError)."
    }

    fun warmupAsync(onDone: ((Boolean) -> Unit)? = null) {
        Thread {
            var delayMs = 10_000L
            var lastOk: Boolean? = null
            while (true) {
                val ok = try {
                    ensureServer()
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
                // Неустранимая ошибка (нет бинаря в APK): повторные попытки
                // не помогут — выходим, чтобы не крутить поток и не спамить в logcat.
                if (serverFatal) {
                    Log.w("MiniUNA-Needle", "warmup остановлен: $serverFatalReason")
                    return@Thread
                }
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

        // Persona first: greetings and small talk never reach the tools.
        if (
            t == "привет" || t.startsWith("привет ") || t.startsWith("привет,") ||
            t == "здравствуй" || t == "здравствуйте" ||
            t.contains("доброе утро") || t.contains("добрый день") || t.contains("добрый вечер")
        ) {
            return IntentResult("PERSONA", 0.99f, mapOf("key" to "greeting"), reasoning = "deterministic persona fast path")
        }
        if (t.contains("как дела")) {
            return IntentResult("PERSONA", 0.99f, mapOf("key" to "howareyou"), reasoning = "deterministic persona fast path")
        }
        if (t.contains("кто ты") || t.contains("что ты такое") || t.contains("расскажи о себе")) {
            return IntentResult("PERSONA", 0.99f, mapOf("key" to "who"), reasoning = "deterministic persona fast path")
        }
        if (t == "спасибо" || t.startsWith("спасибо ") || t.startsWith("спасибо,")) {
            return IntentResult("PERSONA", 0.99f, mapOf("key" to "thanks"), reasoning = "deterministic persona fast path")
        }
        if (t.contains("спокойной ночи")) {
            return IntentResult("PERSONA", 0.99f, mapOf("key" to "night"), reasoning = "deterministic persona fast path")
        }
        if (t == "пока" || t.contains("до связи") || t.contains("до встречи")) {
            return IntentResult("PERSONA", 0.99f, mapOf("key" to "bye"), reasoning = "deterministic persona fast path")
        }
        if (
            t.contains("слушай юну") || t.contains("включи прослушку") ||
            t.contains("начни слушать") || t == "прослушка"
        ) {
            return IntentResult("LISTEN_ON", 0.99f, reasoning = "deterministic listen fast path")
        }
        if (
            t.contains("хватит слушать") || t.contains("выключи прослушку") ||
            t.contains("не слушай") || t.contains("останови прослушку")
        ) {
            return IntentResult("LISTEN_OFF", 0.99f, reasoning = "deterministic listen fast path")
        }

        // Music requests go before the generic app-launch path:
        // "включи музыку" must start playback, not just open an app page.
        if (
            t.contains("включи музыку") ||
            t.contains("включи музычку") ||
            t.contains("поставь музыку") ||
            t.contains("подруби музыку") ||
            t.contains("подруби музон") ||
            t.contains("вруби музыку") ||
            t.contains("вруби музон") ||
            t.contains("давай музыку") ||
            t == "музыка" ||
            t == "музыку" ||
            t == "музон"
        ) {
            return IntentResult("PLAY_MUSIC", 0.995f, reasoning = "deterministic music fast path")
        }

        val playlistMatch = Regex(".*включи плейлист\\s+(.+?)\\s*[.!?]?$").find(t)
        if (playlistMatch != null) {
            val pl = playlistMatch.groupValues[1].trim()
            if (pl.isNotEmpty()) {
                return IntentResult(
                    "PLAY_MUSIC", 0.99f,
                    mapOf("app" to "NeonWave", "playlist" to pl),
                    reasoning = "deterministic playlist fast path"
                )
            }
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

        if (t.startsWith("напомни ")) {
            val body = raw.trim().substringAfter("напомни ").trim()
            var secs: Int? = null
            var text = body
            val throughM = Regex("(?i)^через\\s+(.+)$").find(body)
            if (throughM != null) {
                val rest = throughM.groupValues[1].trim()
                secs = parseDurationSec(rest)
                if (secs == null) {
                    val low = rest.lowercase(Locale.getDefault())
                    secs = when {
                        low.contains("час") -> 3600
                        low.contains("мин") -> 60
                        low.contains("сек") -> 1
                        low.contains("день") || low.contains("дня") || low.contains("сут") -> 86400
                        else -> null
                    }
                }
                if (secs != null) {
                    text = Regex("(?i)^(\\S+\\s+){1,3}").find(rest)
                        ?.let { rest.substring(it.value.length) }?.trim().orEmpty()
                }
            } else {
                val atM = Regex("(?i)^в\\s+(\\d{1,2})[:.](\\d{2})\\s*(.*)$").find(body)
                if (atM != null) {
                    val h = atM.groupValues[1].toInt()
                    val m = atM.groupValues[2].toInt()
                    if (h in 0..23 && m in 0..59) {
                        val now = java.util.Calendar.getInstance()
                        val target = (now.clone() as java.util.Calendar).apply {
                            set(java.util.Calendar.HOUR_OF_DAY, h)
                            set(java.util.Calendar.MINUTE, m)
                            set(java.util.Calendar.SECOND, 0)
                        }
                        if (!target.after(now)) target.add(java.util.Calendar.DAY_OF_YEAR, 1)
                        secs = ((target.timeInMillis - now.timeInMillis) / 1000).toInt()
                        text = atM.groupValues[3].trim()
                    }
                }
            }
            if (secs != null) {
                return IntentResult(
                    "REMIND", 0.97f,
                    mapOf("seconds" to secs.toString(), "text" to text),
                    reasoning = "deterministic remind fast path"
                )
            }
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

        if (
            t.contains("нажми на ") || t.contains("нажми ") ||
            t.contains("тапни ") || t.contains("кликни ")
        ) {
            return IntentResult("SCREEN_TAP", 0.99f, mapOf("text" to raw.trim()), reasoning = "deterministic screen fast path")
        }

        if (t.startsWith("ответь ")) {
            val rest = raw.trim().substringAfter("ответь ").trim()
            val first = rest.substringBefore(" ").trim()
            val appArg = androidTools.resolveInstalledApp(first)?.label.orEmpty()
            val textArg = if (appArg.isNotEmpty()) rest.substringAfter(" ").trim() else rest
            return IntentResult(
                "NOTIF_REPLY", 0.97f,
                mapOf("app" to appArg, "text" to textArg),
                reasoning = "deterministic notifications fast path"
            )
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
            // Имя не распозналось: всё равно идём в OPEN_APP, чтобы ответ
            // был конкретным («не нашла, может …?»), а не общим BLOCK.
            if (app.isNotEmpty()) {
                return IntentResult(
                    intent = "OPEN_APP",
                    confidence = 0.6f,
                    arguments = mapOf("app" to app),
                    reasoning = "unresolved app name, let ToolRegistry explain"
                )
            }
        }

        // Volume percent beats plain up/down: "громче на 20" is a target, not a step.
        parsePercent(raw)?.let { p ->
            if (t.contains("громк") || t.contains("звук") || t.contains("тише") || t.contains("громче")) {
                return IntentResult("VOLUME_PERCENT", 0.99f, mapOf("percent" to p.toString()), reasoning = "deterministic volume fast path")
            }
        }

        // Music pause: explicit stop, never a toggle.
        if (
            t.contains("выключи музыку") || t.contains("выключи музон") ||
            t.contains("останови музыку") || t.contains("останови музон") ||
            t == "стоп музыка" || t == "стоп музон" || t == "стоп"
        ) {
            return IntentResult("MEDIA_PAUSE", 0.99f, reasoning = "deterministic music fast path")
        }

        // Connectivity panels (system sheets with a switch, no special permission).
        if (t.contains("вайфай") || t.contains("вай-фай") || t.contains("вай фай") || t.contains("wi-fi") || t.contains("wifi")) {
            return IntentResult("WIFI_PANEL", 0.97f, reasoning = "deterministic connectivity fast path")
        }
        if (t.contains("блютуз") || t.contains("блютус") || t.contains("bluetooth")) {
            return IntentResult("BT_PANEL", 0.97f, reasoning = "deterministic connectivity fast path")
        }

        // Alarm clock: time and days are parsed here, gaps are asked later.
        if (t.contains("будильник") || t.contains("будильника")) {
            if (t.contains("отмени") || t.contains("удали") || t.contains("выключи") || t.contains("убери")) {
                val args = mutableMapOf<String, String>()
                parseTimeRu(t)?.let { (h, m) ->
                    args["hour"] = h.toString()
                    args["minutes"] = m.toString()
                }
                parseDurationSec(t)?.let { s ->
                    args["snooze_minutes"] = (s / 60).coerceAtLeast(1).toString()
                }
                return IntentResult("ALARM_DISMISS", 0.97f, args, reasoning = "deterministic alarm fast path")
            }
            val args = mutableMapOf<String, String>()
            parseTimeRu(t)?.let { (h, m) ->
                args["hour"] = h.toString()
                args["minutes"] = m.toString()
            }
            parseDaysRu(t)?.let { days ->
                args["days"] = days.joinToString(",")
            }
            return IntentResult("SET_ALARM", 0.97f, args, reasoning = "deterministic alarm fast path")
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
            // Мусор с мизерной уверенностью — сразу «не понял», без диалога.
            if (confidence < CONFIDENCE_FLOOR) return emptyList()
            val gate = confThreshold()
            val out = mutableListOf<IntentResult>()
            val seen = HashSet<String>()
            for (i in 0 until minOf(calls.length(), 4)) {
                val result = parseCall(calls.getJSONObject(i), confidence, reasoning)
                if (result.intent == "UNKNOWN") continue
                if (result.arguments.isEmpty() && result.intent in ARG_REQUIRED) continue
                val key = result.intent + "|" + result.arguments.toSortedMap().toString()
                if (!seen.add(key)) continue
                out.add(result.copy(requiresConfirmation = confidence < gate))
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
            "volume_percent" -> "VOLUME_PERCENT"
            "media_pause" -> "MEDIA_PAUSE"
            "dismiss_alarm" -> "ALARM_DISMISS"
            "cancel_timer" -> "TIMER_CANCEL"
            "wifi_panel" -> "WIFI_PANEL"
            "bluetooth_panel" -> "BT_PANEL"
            "start_listening" -> "LISTEN_ON"
            "stop_listening" -> "LISTEN_OFF"
            "set_alarm" -> "SET_ALARM"
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
            "notif_reply" -> "NOTIF_REPLY"
            "screen_tap" -> "SCREEN_TAP"
            "remind" -> "REMIND"
            "note_list" -> "NOTE_LIST"
            "note_clear" -> "NOTE_CLEAR"
            "shop_add" -> "SHOP_ADD"
            "shop_list" -> "SHOP_LIST"
            "shop_remove" -> "SHOP_REMOVE"
            "calendar_today" -> "CALENDAR_TODAY"
            "calendar_tomorrow" -> "CALENDAR_TOMORROW"
            "calendar_next" -> "CALENDAR_NEXT"
            "note_list" -> "NOTE_LIST"
            "note_clear" -> "NOTE_CLEAR"
            "shop_add" -> "SHOP_ADD"
            "shop_list" -> "SHOP_LIST"
            "shop_remove" -> "SHOP_REMOVE"
            "calendar_today" -> "CALENDAR_TODAY"
            "calendar_tomorrow" -> "CALENDAR_TOMORROW"
            "calendar_next" -> "CALENDAR_NEXT"
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

    private fun ensureServer(): Boolean {
        if (nativeReady) return true
        if (serverFatal) return false

        synchronized(initLock) {
            if (nativeReady) return true
            if (serverFatal) return false
            return try {
                if (!server.ensureStarted()) {
                    lastError = server.lastError
                    // Deterministic failure: binary or tools missing from APK.
                    if (lastError.startsWith("нет файлов needle")) {
                        serverFatal = true
                        serverFatalReason = lastError
                    }
                    nativeReady = false
                    return false
                }
                nativeReady = true
                lastError = "ok"
                Log.i("MiniUNA-Needle", "needle server ready")
                true
            } catch (e: Throwable) {
                lastError = "ensure: ${e.message}"
                Log.w("MiniUNA-Needle", lastError)
                nativeReady = false
                false
            }
        }
    }

    fun restartServer(): Boolean {
        server.abort()
        nativeReady = false
        return ensureServer()
    }

    fun close() {
        server.stop()
        nativeReady = false
    }
}
