package com.maxsc2.miniuna

import android.content.Context
import com.cactus.CactusJNI
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

class NeedleEngine(
    private val context: Context,
    private val fallback: IntentEngine = LocalIntentEngine()
) : IntentEngine {

    private var model: Long = 0L
    private var nativeReady = false
    private var toolsJson: String? = null

    override fun classify(text: String): IntentResult {
        if (!ensureNativeModel()) return fallback.classify(text)
        return try {
            val messages = JSONObject()
                .put("role", "user")
                .put("content", text)
                .let { "[" + it.toString() + "]" }
            val buffer = ByteArray(65536)
            val rc = CactusJNI.nativeComplete(model, messages, buffer, null, toolsJson, null, null)
            if (rc < 0) return fallback.classify(text)
            val end = buffer.indexOf(0)
            val json = String(if (end >= 0) buffer.copyOf(end) else buffer, StandardCharsets.UTF_8).trim()
            parseNeedleResult(json) ?: fallback.classify(text)
        } catch (_: Throwable) {
            fallback.classify(text)
        }
    }

    private fun parseNeedleResult(json: String): IntentResult? {
        val root = JSONObject(json)
        val confidence = root.optDouble("confidence", 0.0).toFloat()
        val calls = root.optJSONArray("function_calls") ?: return null
        if (calls.length() == 0 || confidence < 0.72f) return IntentResult("UNKNOWN", confidence)
        val call = calls.getJSONObject(0)
        val name = call.optString("name")
        val args = call.optJSONObject("arguments")
        val map = mutableMapOf<String, String>()
        if (args != null) {
            args.keys().forEach { key -> map[key] = args.optString(key) }
        }
        val intent = when (name) {
            "get_time" -> "GET_TIME"
            "get_date" -> "GET_DATE"
            "calculate" -> "CALCULATE"
            "open_web" -> "OPEN_WEB"
            "save_note" -> "SAVE_NOTE"
            else -> "UNKNOWN"
        }
        return IntentResult(intent, confidence, map)
    }

    private fun ensureNativeModel(): Boolean {
        if (nativeReady && model != 0L) return true
        return try {
            if (toolsJson == null) {
                toolsJson = context.assets.open("needle_tools.json").bufferedReader().use { it.readText() }
            }
            val asset = context.assets.open("needle3.cact")
            val modelFile = File(context.filesDir, "needle3.cact")
            if (!modelFile.exists() || modelFile.length() == 0L) {
                FileOutputStream(modelFile).use { out -> asset.copyTo(out) }
            } else asset.close()
            model = CactusJNI.nativeInit(modelFile.absolutePath, null, false)
            nativeReady = model != 0L
            nativeReady
        } catch (_: Throwable) {
            false
        }
    }

    fun close() {
        if (model != 0L) {
            try { CactusJNI.nativeDestroy(model) } catch (_: Throwable) {}
            model = 0L
            nativeReady = false
        }
    }
}
