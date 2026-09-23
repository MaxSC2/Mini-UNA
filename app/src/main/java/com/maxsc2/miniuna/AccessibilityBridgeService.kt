package com.maxsc2.miniuna

import android.accessibilityservice.AccessibilityService

class AccessibilityBridgeService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AccessibilityBridgeService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun perform(command: String): Boolean = when (command) {
        "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "LOCK_SCREEN" -> if (android.os.Build.VERSION.SDK_INT >= 28) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else false
        else -> false
    }

    fun tapText(query: String): String {
        val service = this
        val root = try {
            service.rootInActiveWindow
        } catch (_: Throwable) {
            null
        } ?: return "Не вижу экран. Включи службу Mini-UNA."
        val q = query.trim().lowercase(java.util.Locale.getDefault())
        if (q.isBlank()) return "Что нажать?"
        var best: android.view.accessibility.AccessibilityNodeInfo? = null
        var bestLen = Int.MAX_VALUE
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 8) return
            val label = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
                .trim().lowercase(java.util.Locale.getDefault())
            if (label.contains(q) && label.length < bestLen) {
                best = node
                bestLen = label.length
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        return try {
            walk(root, 0)
            var target = best ?: return "Не нашла «" + query.trim() + "» на экране."
            var hops = 0
            while (!target.isClickable && target.parent != null && hops < 6) {
                target = target.parent
                hops++
            }
            if (target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                "Нажала «" + query.trim() + "»."
            } else {
                "Не получилось нажать."
            }
        } catch (_: Throwable) {
            "Не получилось нажать."
        }
    }

    fun dumpScreenText(maxChars: Int = 2000, maxNodes: Int = 60): String {
        val root = try {
            rootInActiveWindow
        } catch (_: Throwable) {
            null
        } ?: return ""
        val out = StringBuilder()
        var seen = 0
        val seenTexts = HashSet<String>()
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null || seen >= maxNodes || out.length >= maxChars) return
            seen++
            val text = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty()).trim()
            if (text.length > 1 && seenTexts.add(text)) {
                if (out.isNotEmpty()) out.append("\n")
                out.append(text.take(160))
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
                if (seen >= maxNodes || out.length >= maxChars) break
            }
        }
        return try {
            walk(root)
            out.toString().take(maxChars)
        } catch (_: Throwable) {
            ""
        }
    }
}
