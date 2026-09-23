package com.maxsc2.miniuna

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

class AndroidTools(private val context: Context) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var cachedApps: List<InstalledApp>? = null
    @Volatile private var cachedAppsAt = 0L

    data class InstalledApp(
        val label: String,
        val packageName: String
    )

    private val aliasMap = mapOf(
        "ютуб" to listOf("youtube"),
        "ю туб" to listOf("youtube"),
        "хром" to listOf("chrome", "google chrome"),
        "телеграм" to listOf("telegram"),
        "телега" to listOf("telegram"),
        "ватсап" to listOf("whatsapp"),
        "вацап" to listOf("whatsapp"),
        "спотифай" to listOf("spotify"),
        "тикток" to listOf("tiktok"),
        "дискорд" to listOf("discord"),
        "инстаграм" to listOf("instagram"),
        "инста" to listOf("instagram"),
        "карты" to listOf("google maps", "maps"),
        "гугл карты" to listOf("google maps", "maps"),
        "каспи" to listOf("kaspi"),
        "халык" to listOf("halyk"),
        "часы" to listOf("clock", "deskclock", "часы", "будильник"),
        "музыка" to listOf("spotify", "yandexmusic", "youtubemusic", "music", "плеер", "player"),
        "музыку" to listOf("spotify", "yandexmusic", "youtubemusic", "music", "плеер", "player"),
        "музон" to listOf("spotify", "yandexmusic", "youtubemusic", "music", "плеер", "player"),
        "яндексмузыка" to listOf("yandexmusic", "яндексмузыка"),
        "ютубмузыка" to listOf("youtubemusic"),
        "неонвейв" to listOf("neonwave", "audioplayer"),
        "неонвейвплеер" to listOf("neonwave", "audioplayer")
    )

    private val musicPriority = listOf(
        "neonwave", "spotify", "yandexmusic", "youtubemusic", "music", "плеер", "player", "аудио", "audio"
    )

    fun installedApps(): List<InstalledApp> {
        val now = System.currentTimeMillis()
        cachedApps?.let { if (now - cachedAppsAt < 30_000L) return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager
            .queryIntentActivities(intent, 0)
            .asSequence()
            .map { info ->
                InstalledApp(
                    label = info.loadLabel(context.packageManager).toString(),
                    packageName = info.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .sortedBy { normalize(it.label) }
            .toList()
            .also { cachedApps = it; cachedAppsAt = System.currentTimeMillis() }
    }

    fun appCount(): Int = installedApps().size

    fun appCatalogForModel(maxApps: Int = 60): String {
        val apps = installedApps().take(maxApps)
        if (apps.isEmpty()) return "No launchable third-party apps were found."

        val aliases = aliasMap.entries.joinToString("; ") { entry ->
            entry.key + " -> " + entry.value.joinToString("/")
        }

        return buildString {
            append("Installed launchable apps: ")
            append(apps.joinToString(", ") { it.label })
            append(". Common Russian aliases: ")
            append(aliases)
            append(". For open_app, choose the closest installed app label; do not invent an app.")
        }
    }

    fun openApp(name: String): String {
        val app = resolveInstalledApp(name)
            ?: return "Не нашла установленное приложение «" + name + "»."

        val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            return "Открываю " + app.label + "."
        }

        return "У приложения «" + app.label + "» нет доступной точки запуска."
    }

    fun resolveInstalledApp(name: String): InstalledApp? {
        val apps = installedApps()
        if (apps.isEmpty()) return null

        val query = normalize(name)
        if (query.isBlank()) return null

        apps.firstOrNull { normalize(it.label) == query }?.let { return it }

        val aliasTargets = aliasMap[query].orEmpty()
        if (aliasTargets.isNotEmpty()) {
            apps.firstOrNull { app ->
                val label = normalize(app.label)
                aliasTargets.any { target ->
                    label == normalize(target) || label.contains(normalize(target))
                }
            }?.let { return it }
        }

        apps.firstOrNull {
            normalize(it.label).contains(query) || query.contains(normalize(it.label))
        }?.let { return it }

        apps.firstOrNull {
            normalize(it.packageName.substringAfterLast('.')).contains(query)
        }?.let { return it }

        return null
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
            .lowercase(Locale.getDefault())
            .replace(Regex("[^\\p{L}\\p{N}]+"), "")
    }

    fun openSettings(section: String): String {
        val s = section.trim().lowercase(Locale.getDefault())
        val action = when {
            s.contains("wifi") || s.contains("вайфай") || s.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
            s.contains("bluetooth") || s.contains("блютуз") -> Settings.ACTION_BLUETOOTH_SETTINGS
            s.contains("звук") || s.contains("громк") -> Settings.ACTION_SOUND_SETTINGS
            s.contains("экран") || s.contains("display") -> Settings.ACTION_DISPLAY_SETTINGS
            s.contains("доступ") || s.contains("accessibility") -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Открываю настройки."
        } catch (_: Throwable) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Открываю системные настройки."
        }
    }

    fun openWeb(target: String): String {
        val value = target.trim()
        val url = if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "https://www.google.com/search?q=" + Uri.encode(value)
        }

        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Открываю."
        } catch (_: ActivityNotFoundException) {
            "На устройстве нет приложения, которое может открыть ссылку."
        }
    }

    fun setTimer(seconds: Int): String {
        val safe = seconds.coerceIn(1, 24 * 60 * 60)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, safe)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            "Ставлю таймер на " + formatDuration(safe) + "."
        } catch (_: ActivityNotFoundException) {
            "На устройстве не найдено приложение с поддержкой системного таймера."
        }
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds % 3600 == 0 -> (seconds / 3600).toString() + " ч."
        seconds % 60 == 0 -> (seconds / 60).toString() + " мин."
        else -> seconds.toString() + " сек."
    }

    fun volumeUp(): String {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        return "Медиа-громкость увеличена."
    }

    fun volumeDown(): String {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        return "Медиа-громкость уменьшена."
    }

    fun volumeMute(): String {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
        return "Медиа-звук переключён."
    }

    fun setVolumePercent(percent: Int): String {
        val p = percent.coerceIn(0, 100)
        return try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val idx = ((max * p) / 100f).roundToInt().coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, AudioManager.FLAG_SHOW_UI)
            "Громкость $p%."
        } catch (_: Throwable) {
            "Не получилось изменить громкость."
        }
    }

    fun setAlarm(hour: Int, minute: Int, days: List<Int>?, label: String = "Mini-UNA"): String {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!days.isNullOrEmpty()) {
                putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val hh = h.toString().padStart(2, '0')
            val mm = m.toString().padStart(2, '0')
            "Будильник на $hh:$mm" + daysText(days) + "."
        } catch (_: ActivityNotFoundException) {
            "На устройстве нет приложения часов с будильником."
        }
    }

    private fun daysText(days: List<Int>?): String {
        if (days.isNullOrEmpty()) return ""
        val weekdays = setOf(2, 3, 4, 5, 6)
        val weekend = setOf(1, 7)
        val set = days.toSet()
        return when {
            set == weekdays -> ", по будням"
            set == weekend -> ", по выходным"
            set.size >= 7 -> ", каждый день"
            else -> ""
        }
    }

    fun neonPlay(playlist: String? = null): Boolean {
        return try {
            val intent = Intent("com.example.audio_player.AUTOPLAY").apply {
                setClassName("com.example.audio_player", "com.example.audio_player.MainActivity")
                if (!playlist.isNullOrBlank()) putExtra("playlist", playlist)
                putExtra("source", "mini-una")
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isNeonInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("com.example.audio_player", 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun playMusic(app: String? = null, playlist: String? = null): String {
        val query = app?.trim().orEmpty()
        // NeonWave — наш плеер: мост автовоспроизведения (без задержек в плеере).
        val wantsNeon = playlist != null ||
            query.isBlank() ||
            query.contains("неон", ignoreCase = true) ||
            query.contains("neon", ignoreCase = true)
        if (wantsNeon && isNeonInstalled()) {
            if (neonPlay(playlist)) {
                return if (playlist.isNullOrBlank()) "Включаю музыку в NeonWave."
                else "Включаю плейлист «$playlist» в NeonWave."
            }
            // Мост не встал (старый NeonWave?) — дальше общий путь.
        }

        val target = if (query.isNotBlank()) query else "музыку"
        val resolved = resolveInstalledApp(target)
            ?: musicPriority.firstNotNullOfOrNull { hint -> resolveInstalledApp(hint) }
            ?: return "Не нашла музыкальное приложение. Установи NeonWave, Spotify или Яндекс Музыку."

        return try {
            context.packageManager.getLaunchIntentForPackage(resolved.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            // Плееру нужно время на запуск сессии: проверяем в фоне, дожимаем
            // play, а когда звук пошёл — возвращаем Юну наверх.
            Thread {
                try {
                    Thread.sleep(1500)
                    repeat(3) {
                        if (audioManager.isMusicActive()) {
                            bringAppFront()
                            return@Thread
                        }
                        mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                        Thread.sleep(1500)
                    }
                    if (audioManager.isMusicActive()) bringAppFront()
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true; start() }
            "Включаю музыку в " + resolved.label + "."
        } catch (_: Throwable) {
            "Не получилось открыть «" + resolved.label + "»."
        }
    }

    private fun bringAppFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                context.startActivity(intent)
            } else {
                Handler(Looper.getMainLooper()).post {
                    try {
                        context.startActivity(intent)
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun mediaToggle(): String =
        if (mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) "Переключила воспроизведение."
        else "Не получилось отправить медиа-кнопку."

    fun mediaNext(): String =
        if (mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)) "Следующий трек."
        else "Не получилось отправить медиа-кнопку."

    fun mediaPrevious(): String =
        if (mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)) "Предыдущий трек."
        else "Не получилось отправить медиа-кнопку."

    fun pauseMusic(): String =
        if (mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)) "Музыка остановлена."
        else "Не получилось остановить. Рядом нет активного плеера."

    fun dismissAlarm(hour: Int?, minute: Int?, snoozeMinutes: Int?, days: List<Int>? = null): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                if (hour != null && minute != null) {
                    putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                    putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                }
                if (snoozeMinutes != null && snoozeMinutes > 0) {
                    putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, snoozeMinutes)
                }
                if (!days.isNullOrEmpty()) {
                    putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Выключаю будильник."
        } catch (_: Throwable) {
            "Не получилось. Открой приложение Часы и выключи вручную."
        }
    }

    fun openClock(): String {
        val app = resolveInstalledApp("часы")
        if (app != null) return openApp(app.label)
        return "Таймеры отключаются в приложении Часы. Открыть его?"
    }

    fun dismissTimer(): String {
        return try {
            context.startActivity(
                Intent(AlarmClock.ACTION_DISMISS_TIMER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Выключаю таймер."
        } catch (_: Throwable) {
            openClock()
        }
    }

    fun connectivityPanel(kind: String): String {
        val action = when (kind) {
            "bluetooth" -> "android.settings.panel.action.BLUETOOTH"
            else -> "android.settings.panel.action.WIFI"
        }
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (kind == "bluetooth") "Открываю панель Bluetooth." else "Открываю панель Wi-Fi."
            } else {
                openSettings(kind)
            }
        } catch (_: Throwable) {
            openSettings(kind)
        }
    }

    fun screenContext(maxChars: Int = 800): String {
        val service = AccessibilityBridgeService.instance ?: return ""
        return service.dumpScreenText(maxChars)
    }

    fun screenText(): String {
        if (AccessibilityBridgeService.instance == null) {
            return "Для чтения экрана включи службу Mini-UNA в настройках специальных возможностей."
        }
        val text = screenContext(500)
        return if (text.isBlank()) "На экране нет читаемого текста."
        else "На экране:\n" + text
    }

    fun recentNotifications(): String {
        if (NotifListenerService.instance == null) {
            return "Доступ к уведомлениям выключен. Открой доступ в настройках уведомлений."
        }
        val items = NotifListenerService.snapshot().take(5)
        if (items.isEmpty()) return "Уведомлений пока нет."
        return items.joinToString("\n\n") { item ->
            appLabel(item.packageName) + ": " + item.title + " — " + item.text
        }.take(900)
    }

    fun followNotifications(app: String): String {
        val resolved = resolveInstalledApp(app) ?: return "Не нашла приложение «" + app + "»."
        prefs().edit().putStringSet(
            "notif_fav",
            (prefs().getStringSet("notif_fav", emptySet()).orEmpty() + resolved.packageName)
        ).apply()
        return "Слежу за уведомлениями " + resolved.label + ". Буду озвучивать новые."
    }

    fun unfollowNotifications(app: String): String {
        val resolved = resolveInstalledApp(app)
        val current = prefs().getStringSet("notif_fav", emptySet()).orEmpty()
        if (resolved == null) {
            if (current.isEmpty()) return "Список отслеживания пуст."
            prefs().edit().putStringSet("notif_fav", emptySet()).apply()
            return "Перестала следить за всеми."
        }
        prefs().edit().putStringSet("notif_fav", current - resolved.packageName).apply()
        return "Больше не слежу за " + resolved.label + "."
    }

    fun youtubeSearch(raw: String): String {
        var q = raw.trim()
        for (w in listOf("на ютьюбе", "на ютубе", "в ютубе", "ютуб", "пожалуйста", "включи", "найди", "поищи", "покажи")) {
            q = q.replace(w, " ", ignoreCase = true)
        }
        q = q.replace(Regex("\\s+"), " ").trim()
        if (q.isBlank()) return openApp("youtube")
        val url = "https://www.youtube.com/results?search_query=" + Uri.encode(q)
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Ищу «" + q + "» на YouTube."
        } catch (_: ActivityNotFoundException) {
            "На устройстве нет приложения, которое может открыть ссылку."
        }
    }

    fun tgShare(raw: String): String {
        var text = raw.trim()
        for (w in listOf("в телеграм", "в телегу", "телеграм", "телегу", "отправь", "напиши", "пожалуйста")) {
            text = text.replace(w, " ", ignoreCase = true)
        }
        text = text.replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return "Что отправить в Telegram?"
        val pkgs = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")
        val pkg = pkgs.firstOrNull {
            try {
                context.packageManager.getPackageInfo(it, 0)
                true
            } catch (_: Throwable) {
                false
            }
        } ?: return "Telegram не установлен."
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Отправляю в Telegram."
        } catch (_: Throwable) {
            "Не получилось открыть Telegram."
        }
    }

    fun listenOn(): String {
        return try {
            ContextCompat.startForegroundService(context, Intent(context, HotwordService::class.java))
            "Слушаю. Скажи «Юна» и команду."
        } catch (_: Throwable) {
            "Не получилось включить прослушку."
        }
    }

    fun listenOff(): String {
        return try {
            context.stopService(Intent(context, HotwordService::class.java))
            "Прослушка выключена."
        } catch (_: Throwable) {
            "Не получилось выключить прослушку."
        }
    }

    fun remind(text: String, secondsFromNow: Int): String {
        val secs = secondsFromNow.coerceIn(10, 24 * 60 * 60)
        return try {
            val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra("text", text.ifBlank { "Напоминание!" })
                putExtra("id", id)
            }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
            val pi = android.app.PendingIntent.getBroadcast(context, id, intent, flags)
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.set(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + secs * 1000L,
                pi
            )
            "Напомню через " + formatDuration(secs) + ": «" + text.ifBlank { "без текста" } + "»."
        } catch (_: Throwable) {
            "Не получилось поставить напоминание."
        }
    }

    private fun prefs() = context.getSharedPreferences("mini_una", Context.MODE_PRIVATE)

    private fun appLabel(packageName: String): String = try {
        context.packageManager.getApplicationInfo(packageName, 0)
            .loadLabel(context.packageManager).toString()
    } catch (_: Throwable) {
        packageName
    }

    private fun mediaKey(keyCode: Int): Boolean = try {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        true
    } catch (_: Throwable) {
        false
    }

    fun accessibility(command: String): String {
        val service = AccessibilityBridgeService.instance
        if (service == null) {
            return "Для этого действия включи службу Mini-UNA в настройках специальных возможностей."
        }
        return if (service.perform(command)) {
            when (command) {
                "BACK" -> "Назад."
                "HOME" -> "Домой."
                "RECENTS" -> "Открываю недавние приложения."
                "LOCK_SCREEN" -> "Блокирую экран."
                else -> "Готово."
            }
        } else {
            "Android не разрешил системное действие."
        }
    }

    fun calculate(expression: String): String {
        val cleaned = expression.replace(',', '.').replace(" ", "")
        val match = Regex("^(-?\\d+(?:\\.\\d+)?)([+\\-*/])(-?\\d+(?:\\.\\d+)?)$").find(cleaned)
            ?: return "Пока считаю выражения вида 12+7."

        val a = match.groupValues[1].toDouble()
        val op = match.groupValues[2]
        val b = match.groupValues[3].toDouble()

        if (op == "/" && b == 0.0) return "На ноль делить не буду."

        val r = when (op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> a / b
            else -> return "Неизвестная операция."
        }

        val shown = if (r == r.roundToInt().toDouble()) r.roundToInt().toString()
        else "%.4f".format(Locale.US, r)

        return "Ответ: " + shown + "."
    }
}
