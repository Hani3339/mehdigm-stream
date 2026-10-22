package com.shahid4u

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * تُرمى عندما يكون الموقع في فترة تهدئة طويلة؛ الطلب يفشل سريعاً بدل أن
 * يعلّق التطبيق لدقائق.
 */
class SiteCooldownException(val remainingMs: Long) :
    Exception("الموقع في فترة تهدئة: ${remainingMs / 1000} ثانية")

/**
 * بوابة واحدة تمرّ عبرها **كل** طلبات shaahidd4u.net:
 *
 *  - طلب واحد فقط في كل مرة (Mutex) — لا توازٍ إطلاقاً.
 *  - فاصل إلزامي بين بدايات الطلبات (1.5s ويزيد تلقائياً عند الاشتباه).
 *  - عند 403/challenge: تهدئة تصاعدية تصل إلى 15 دقيقة، ولا تُرسل طلبات
 *    أثناءها (المحاولة أثناء الحظر تطيله).
 *  - بعد سلسلة نجاحات يعود الفاصل تدريجياً إلى الحد الأدنى.
 *
 * هذا هو الحل الدائم لمشكلة rate-limit/حظر الـIP: كل طلبات الموقع تمر من
 * هنا، وكل الطلبات الخارجية (extractors) لا تمر من هنا لأنها ليست الموقع.
 */
object SiteGate {
    private const val MIN_INTERVAL_MS = 1_500L
    private const val MAX_INTERVAL_MS = 15_000L
    private const val BASE_PENALTY_MS = 45_000L
    private const val MAX_COOLDOWN_MS = 15 * 60_000L
    private const val MAX_BLOCKING_WAIT_MS = 20_000L
    private const val SUCCESS_BEFORE_RELAX = 6

    private val mutex = Mutex()

    @Volatile private var earliestAt = 0L
    @Volatile private var intervalMs = MIN_INTERVAL_MS
    @Volatile private var cooldownUntil = 0L
    @Volatile private var penalties = 0
    @Volatile private var streak = 0

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) {
            val wait = cooldownUntil - now
            if (wait > MAX_BLOCKING_WAIT_MS) throw SiteCooldownException(wait)
            delay(wait)
        }

        val wait = earliestAt - System.currentTimeMillis()
        if (wait > 0) delay(wait)

        try {
            block().also { onSuccess() }
        } finally {
            earliestAt = System.currentTimeMillis() + intervalMs + Random.nextLong(0L, 400L)
        }
    }

    private fun onSuccess() {
        streak++
        if (streak >= SUCCESS_BEFORE_RELAX) {
            streak = 0
            penalties = 0
            intervalMs = (intervalMs - 250L).coerceAtLeast(MIN_INTERVAL_MS)
        }
    }

    /** يُستدعى بعد 403/challenge (challenge=true) أو 429 (challenge=false). */
    fun penalize(challenge: Boolean) {
        penalties = (penalties + 1).coerceAtMost(6)
        intervalMs = (intervalMs * 2L).coerceAtMost(MAX_INTERVAL_MS)
        streak = 0
        val base = if (challenge) BASE_PENALTY_MS else BASE_PENALTY_MS / 2L
        val wait = (base * (1L shl (penalties - 1))).coerceAtMost(MAX_COOLDOWN_MS)
        cooldownUntil = System.currentTimeMillis() + wait
    }

    fun isCoolingDown(): Boolean = System.currentTimeMillis() < cooldownUntil

    fun cooldownRemaining(): Long =
        (cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    fun describe(): String =
        if (isCoolingDown()) "cooldown=${cooldownRemaining() / 1000}s"
        else "interval=${intervalMs}ms"
}
