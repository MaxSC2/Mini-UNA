package com.maxsc2.miniuna

class ToolRegistry(
    private val time: () -> String,
    private val date: () -> String,
    private val calculate: (String) -> String,
    private val openWeb: (String) -> Unit,
    private val saveNote: (String) -> Unit
) {
    fun execute(result: IntentResult): String? = when (result.intent) {
        "GET_TIME" -> time()
        "GET_DATE" -> date()
        "CALCULATE" -> calculate(result.arguments["expression"].orEmpty())
        "OPEN_WEB" -> { openWeb(result.arguments["target"].orEmpty()); "Открываю." }
        "SAVE_NOTE" -> { saveNote(result.arguments["note"].orEmpty()); "Записала. Заметка сохранена." }
        else -> null
    }
}
