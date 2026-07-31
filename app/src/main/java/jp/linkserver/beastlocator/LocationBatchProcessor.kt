package jp.linkserver.beastlocator

/**
 * Processes a fused-location batch from oldest to newest.
 *
 * Elapsed realtime is preferred because it is unaffected by wall-clock changes. Some OEM
 * locations do not provide it, so a batch containing any missing monotonic timestamp falls back
 * to wall time for the whole batch. Returning false from [consume] stops the remaining work.
 */
internal object LocationBatchProcessor {
    fun <T> processOldestFirst(
        items: List<T>,
        elapsedRealtimeNanos: (T) -> Long,
        wallTimeMillis: (T) -> Long,
        consume: (T) -> Boolean
    ) {
        for (item in oldestFirst(items, elapsedRealtimeNanos, wallTimeMillis)) {
            if (!consume(item)) return
        }
    }

    internal fun <T> oldestFirst(
        items: List<T>,
        elapsedRealtimeNanos: (T) -> Long,
        wallTimeMillis: (T) -> Long
    ): List<T> {
        if (items.size < 2) return items.toList()

        val useElapsedRealtime = items.all { elapsedRealtimeNanos(it) > 0L }
        return items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<T>> { indexed ->
                    val timestamp = if (useElapsedRealtime) {
                        elapsedRealtimeNanos(indexed.value)
                    } else {
                        wallTimeMillis(indexed.value)
                    }
                    timestamp.takeIf { it > 0L } ?: Long.MAX_VALUE
                }.thenBy { it.index }
            )
            .map { it.value }
    }
}
