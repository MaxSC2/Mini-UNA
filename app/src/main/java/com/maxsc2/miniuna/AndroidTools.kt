package com.maxsc2.miniuna

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

class AndroidTools(private val context: Context) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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
        "халык" to listOf("halyk")
    )

    fun installedApps(): List<InstalledApp> {
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
    }

    fun appCount(): Int = installedApps().size

    fun appCatalogForModel(maxApps: Int = 80): String {
        val apps = installedApps().take(maxApps)
        if (apps.isEmpty()) return "No launchable third-party apps were found."

        val aliases = aliasMap.entries.joinToString("; ") { entry ->
            entry.key + " -> " + entry.value.joinToString("/")
        }

        return buildString {
            append("Installed launchable apps: ")
            append(apps.joinToString(", ") { app -> app.label + " [" + app.packageName + "]" })
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
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
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
