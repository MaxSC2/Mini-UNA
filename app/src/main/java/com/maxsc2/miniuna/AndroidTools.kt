package com.maxsc2.miniuna

import android.provider.AlarmClock
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.net.Uri
import java.util.Locale
import kotlin.math.roundToInt

class AndroidTools(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val appPackages = mapOf(
        "telegram" to "org.telegram.messenger",
        "телеграм" to "org.telegram.messenger",
        "whatsapp" to "com.whatsapp",
        "ватсап" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "ютуб" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "хром" to "com.android.chrome",
        "spotify" to "com.spotify.music",
        "спотифай" to "com.spotify.music",
        "калькулятор" to "com.google.android.calculator"
    )

    fun openApp(name: String): String {
        val normalized = name.trim().lowercase(Locale.getDefault())
        val packageName = appPackages[normalized] ?: normalized.takeIf { it.contains('.') }
        val launch = packageName?.let { context.packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            return "Открываю " + name + "."
        }

        val byLabel = context.packageManager.getInstalledApplications(0)
            .firstOrNull { app ->
                context.packageManager.getApplicationLabel(app).toString()
                    .lowercase(Locale.getDefault()) == normalized
            }
        val byLabelIntent = byLabel?.let { context.packageManager.getLaunchIntentForPackage(it.packageName) }
        if (byLabelIntent != null) {
            byLabelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(byLabelIntent)
            return "Открываю " + name + "."
        }

        return "Не нашла приложение «" + name + "»."
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
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Открываю."
    }

    fun setTimer(seconds: Int): String {
        val safe = seconds.coerceIn(1, 24 * 60 * 60)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, safe)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Ставлю таймер на " + safe + " секунд."
    }

    fun volumeUp(): String {
        audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        return "Громкость увеличена."
    }

    fun volumeDown(): String {
        audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        return "Громкость уменьшена."
    }

    fun volumeMute(): String {
        audioManager.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
        return "Переключила звук."
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
