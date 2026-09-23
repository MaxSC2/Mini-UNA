package com.maxsc2.miniuna

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import java.text.Normalizer
import java.util.Locale

data class InstalledApp(
    val label: String,
    val packageName: String
)

class InstalledAppCatalog(private val context: Context) {

    @Volatile private var cached: List<InstalledApp>? = null
    @Volatile private var cachedAt = 0L

    fun list(): List<InstalledApp> {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < 30_000L) return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info: ResolveInfo ->
                val label = info.loadLabel(context.packageManager).toString().trim()
                val packageName = info.activityInfo?.packageName.orEmpty()
                if (label.isBlank() || packageName.isBlank()) null
                else InstalledApp(label, packageName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .also { cached = it; cachedAt = System.currentTimeMillis() }
    }

    fun resolve(userName: String): InstalledApp? {
        val query = normalize(userName)
        if (query.isBlank()) return null

        val apps = list()

        apps.firstOrNull { normalize(it.label) == query }?.let { return it }

        val aliases = mapOf(
            "ютуб" to listOf("youtube"),
            "ютуб музыка" to listOf("youtube music"),
            "хром" to listOf("chrome", "google chrome"),
            "телеграм" to listOf("telegram"),
            "телега" to listOf("telegram"),
            "ватсап" to listOf("whatsapp"),
            "вотсап" to listOf("whatsapp"),
            "спотифай" to listOf("spotify"),
            "тикток" to listOf("tiktok"),
            "вк" to listOf("вконтакте", "vk"),
            "вконтакте" to listOf("vk", "vkontakte"),
            "яндекс" to listOf("яндекс"),
            "яндекс музыка" to listOf("яндекс музыка"),
            "музыка" to listOf("spotify", "yandex music", "youtube music", "музыка", "плеер"),
            "музыку" to listOf("spotify", "yandex music", "youtube music", "музыка", "плеер"),
            "музон" to listOf("spotify", "yandex music", "youtube music", "музыка", "плеер")
        )

        val wanted = aliases[query].orEmpty().map(::normalize)
        apps.firstOrNull { app ->
            normalize(app.label) in wanted ||
                wanted.any { target ->
                    normalize(app.label).contains(target) || target.contains(normalize(app.label))
                }
        }?.let { return it }

        return apps.firstOrNull {
            val label = normalize(it.label)
            label.contains(query) || query.contains(label)
        }
    }

    fun promptCatalog(maxApps: Int = 48): String {
        val apps = list().take(maxApps)
        if (apps.isEmpty()) return "No launchable installed apps were found."
        return apps.joinToString(
            prefix = "Installed launchable apps: ",
            separator = "; "
        ) { it.label }
    }

    fun summary(): String {
        val apps = list()
        if (apps.isEmpty()) return "Приложения не найдены."
        val shown = apps.take(24).joinToString(", ") { it.label }
        val suffix = if (apps.size > 24) " и ещё ${apps.size - 24}" else ""
        return "Найдено приложений: ${apps.size}. $shown$suffix."
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.trim().lowercase(Locale.getDefault()), Normalizer.Form.NFKC)
            .replace('ё', 'е')
            .replace(Regex("""[^\p{L}\p{N}.]+"""), " ")
            .trim()
    }
}
