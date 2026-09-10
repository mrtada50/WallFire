package com.example.netguardlite.data

import android.net.TrafficStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow

/**
 * يراقب استهلاك كل UID بشكل دوري، ويحدد هل التطبيق "نشط" حالياً
 * عن طريق قياس الفرق بين قراءتين متتاليتين (delta > 0 يعني فيه نقل بيانات فعلي الآن).
 */
object TrafficMonitor {

    data class UsageSnapshot(
        val uid: Int,
        val rxBytes: Long,
        val txBytes: Long,
        val isActiveNow: Boolean
    )

    fun observe(uids: List<Int>, intervalMs: Long = 2000L): Flow<Map<Int, UsageSnapshot>> = flow {
        val lastTotals = HashMap<Int, Long>()

        while (true) {
            val snapshot = HashMap<Int, UsageSnapshot>()

            for (uid in uids) {
                val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
                val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
                val total = rx + tx
                val previous = lastTotals[uid] ?: total
                val isActive = total > previous

                lastTotals[uid] = total
                snapshot[uid] = UsageSnapshot(uid, rx, tx, isActive)
            }

            emit(snapshot)
            delay(intervalMs)
        }
    }
}
