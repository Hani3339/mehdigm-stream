package com.tuktuk

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Run suspending work concurrently while keeping requests to a provider bounded. */
internal suspend fun <T, R> mapBounded(
    items: List<T>,
    parallelism: Int,
    transform: suspend (T) -> R,
): List<R> = coroutineScope {
    if (items.isEmpty()) return@coroutineScope emptyList<R>()
    val semaphore = Semaphore(parallelism.coerceAtLeast(1))
    items.map { item ->
        async {
            semaphore.withPermit { transform(item) }
        }
    }.awaitAll()
}
