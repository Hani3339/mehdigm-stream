package com.tuktuk

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TukTukParallelTest {
    @Test
    fun performsSuspendingWorkConcurrentlyWithinTheLimitAndPreservesOrder() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()

        val result = mapBounded((1..20).toList(), parallelism = 4) { value ->
            val nowActive = active.incrementAndGet()
            peak.updateAndGet { current -> maxOf(current, nowActive) }
            try {
                delay(20)
                value * 2
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals((1..20).map { it * 2 }, result)
        assertTrue("expected concurrent requests, observed ${peak.get()}", peak.get() > 1)
        assertTrue("parallelism exceeded the configured cap", peak.get() <= 4)
    }
}
