package com.maxsc2.miniuna

data class IntentResult(
    val intent: String,
    val confidence: Float,
    val arguments: Map<String, String> = emptyMap()
)
