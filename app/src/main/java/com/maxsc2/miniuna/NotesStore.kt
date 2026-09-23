package com.maxsc2.miniuna

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class NotesStore(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("mini_una", Context.MODE_PRIVATE)

    data class Note(val text: String, val time: Long)

    fun addNote(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        try {
            val arr = JSONArray(prefs().getString("notes_list", "[]"))
            arr.put(JSONObject().put("text", clean).put("time", System.currentTimeMillis()))
            while (arr.length() > 50) arr.remove(0)
            prefs().edit().putString("notes_list", arr.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    fun listNotes(limit: Int = 10): List<Note> {
        return try {
            // Миграция со старого одиночного ключа.
            prefs().getString("last_note", null)?.let { legacy ->
                if (prefs().getString("notes_list", null) == null && legacy.isNotBlank()) {
                    addNote(legacy)
                }
            }
            val arr = JSONArray(prefs().getString("notes_list", "[]"))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text").trim()
                if (text.isBlank()) null else Note(text, o.optLong("time"))
            }.takeLast(limit).reversed()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clearNotes() {
        try {
            prefs().edit().remove("notes_list").remove("last_note").apply()
        } catch (_: Throwable) {
        }
    }

    fun canonList(raw: String): String {
        val t = raw.trim().lowercase(java.util.Locale.getDefault())
        return when {
            t.isBlank() -> "покупки"
            t.contains("покуп") || t.contains("магазин") || t.contains("продукт") -> "покупки"
            else -> raw.trim()
        }
    }

    fun addShop(list: String, item: String): Boolean {
        val clean = item.trim().trim('.', ',', '!', '?')
        if (clean.isBlank()) return false
        return try {
            val all = JSONObject(prefs().getString("shop_lists", "{}"))
            val name = canonList(list)
            val arr = all.optJSONArray(name) ?: JSONArray()
            arr.put(clean)
            all.put(name, arr)
            prefs().edit().putString("shop_lists", all.toString()).apply()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun listShop(list: String): List<String> {
        return try {
            val all = JSONObject(prefs().getString("shop_lists", "{}"))
            val arr = all.optJSONArray(canonList(list)) ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifBlank { null } }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun removeShop(list: String, item: String): Boolean {
        return try {
            val all = JSONObject(prefs().getString("shop_lists", "{}"))
            val name = canonList(list)
            val arr = all.optJSONArray(name) ?: return false
            val want = item.trim().lowercase(java.util.Locale.getDefault())
            for (i in 0 until arr.length()) {
                if (arr.optString(i).trim().lowercase(java.util.Locale.getDefault()) == want) {
                    arr.remove(i)
                    all.put(name, arr)
                    prefs().edit().putString("shop_lists", all.toString()).apply()
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }
}
