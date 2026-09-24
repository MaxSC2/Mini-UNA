package com.maxsc2.miniuna

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Периодические вотчеры: утренняя сводка по расписанию + перезапуск после ребута.
class WatcherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val p = context.getSharedPreferences("mini_una", Context.MODE_PRIVATE)
                val h = p.getInt("briefing_hour", -1)
                val m = p.getInt("briefing_minute", -1)
                if (h in 0..23 && m in 0..59) {
                    try {
                        AndroidTools(context).scheduleBriefing(h, m)
                    } catch (_: Throwable) {
                    }
                }
            }
            ACTION_BRIEFING -> {
                val text = try {
                    AndroidTools(context).morningBriefing()
                } catch (_: Throwable) {
                    "Доброе утро!"
                }
                try {
                    ReminderReceiver.announce(context, "Утренняя сводка", text, 9100, text)
                } catch (_: Throwable) {
                }
            }
        }
    }

    companion object {
        const val ACTION_BRIEFING = "com.maxsc2.miniuna.BRIEFING"
    }
}
