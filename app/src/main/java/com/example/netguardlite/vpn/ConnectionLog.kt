package com.example.netguardlite.vpn

import androidx.compose.runtime.mutableStateListOf

data class ConnectionAttempt(
    val packageName: String,
    val appName: String,
    val destination: String,
    val protocol: String,
    val timestampMillis: Long
)

/**
 * سجل بسيط بالذاكرة لمحاولات الاتصال من التطبيقات المحجوبة.
 * يُمسح تلقائياً عند إيقاف الخدمة أو إغلاق التطبيق.
 */
object ConnectionLog {

    private const val MAX_ENTRIES = 50
    private const val DEDUPE_WINDOW_MS = 5000L

    val entries = mutableStateListOf<ConnectionAttempt>()
    private val recentKeys = HashMap<String, Long>()

    @Synchronized
    fun record(packageName: String, appName: String, destination: String, protocol: String) {
        val key = "$packageName|$destination|$protocol"
        val now = System.currentTimeMillis()
        val last = recentKeys[key]
        if (last != null && now - last < DEDUPE_WINDOW_MS) return
        recentKeys[key] = now

        if (recentKeys.size > 500) {
            val cutoff = now - DEDUPE_WINDOW_MS
            recentKeys.entries.removeAll { it.value < cutoff }
        }

        entries.add(0, ConnectionAttempt(packageName, appName, destination, protocol, now))
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.size - 1)
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        recentKeys.clear()
    }
}
