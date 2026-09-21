package com.maxsc2.miniuna

class LocalIntentEngine : IntentEngine {
    override fun classify(text: String): IntentResult {
        val t = text.trim().lowercase()
        return when {
            t.contains("время") || t.contains("который час") -> IntentResult("GET_TIME", 0.99f)
            t.contains("дата") || t.contains("какое сегодня число") -> IntentResult("GET_DATE", 0.99f)
            t.startsWith("открой ") || t.startsWith("зайди на ") -> IntentResult("OPEN_WEB", 0.96f, mapOf("target" to text.substringAfter(" ").trim()))
            t.startsWith("посчитай ") || t.startsWith("вычисли ") -> IntentResult("CALCULATE", 0.96f, mapOf("expression" to text.substringAfter(" ").trim()))
            t.startsWith("заметка ") || t.startsWith("запиши ") -> IntentResult("SAVE_NOTE", 0.95f, mapOf("note" to text.substringAfter(" ").trim()))
            t.contains("что ты умеешь") || t.contains("помощь") -> IntentResult("HELP", 0.98f)
            else -> IntentResult("UNKNOWN", 0.0f)
        }
    }
}
