package com.maxsc2.miniuna

interface IntentEngine {
    fun classify(text: String): IntentResult
}
