package com.shahid4u

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * تُرمى عندما يكون الموقع في فترة تهدئة؛ الطلب يفشل سريعاً بدل أن يعلّق التطبيق.
 */
class SiteCooldownException(val remainingMs: Long) :
    Exception("الموقع في فترة تهدئة: ${remainingMs / 1000} ثانية")

/**
 * بوابة واحدة تمرّ عبرها **كل** طلبات shaahidd4u.net:
 *
 *  - طلب واحد فقط في كل مرة (Mutex) — لا توازٍ إطلاقاً.
 *  - فاصل قصير بين الطلبات (~1.2s) لأن عدد الطلبات نفسه صار قليلاً جداً.
 *  - حد انفجار: أكثر من 6 طلبات في 15 ثانية ⇒ إبطاء إلى 4 ثوانٍ.
 *  - عند 403/challenge/429: توقف تصاعدي 2د ← 10د ← 30د ← 60د، وفشل سريع
 *    بدل إعادة المحاولة (المحاولة أثناء الحظر تطيله).
 *
 * الهدف: لا نرسل أكثر من بضعة طلبات لكل إجراء، فلا يصل الموقع لحد الحظر.
 */
object SiteGate {
    private const val MIN_INTERVAL_MS = 1_200L
    private const val SLOW_INTERVAL_MS = 4_000L
    private const val MAX_BLOCKING_WAIT_MS = 8_000L
    private const val BURST_WINDOW_MS = 20_000L
    private const val BURST_LIMIT = 12
    private const val SUCCESS_BEFORE_RELAX = 10

    private val mutex = Mutex()
    private val recent = ArrayDeque<Long>()

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

        while (recent.isNotEmpty() && now - recent.first() > BURST_WINDOW_MS) {
            recent.removeFirst()
        }
        val spacing = if (recent.size >= BURST_LIMIT) SLOW_INTERVAL_MS else intervalMs

        val wait = earliestAt - System.currentTimeMillis()
        if (wait > 0) delay(wait)

        val started = System.currentTimeMillis()
        recent.addLast(started)
        try {
            block().also { onSuccess() }
        } finally {
            earliestAt = started + spacing + Random.nextLong(0L, 500L)
        }
    }

    private fun onSuccess() {
        streak++
        if (streak >= SUCCESS_BEFORE_RELAX) {
            streak = 0
            penalties = 0
            intervalMs = MIN_INTERVAL_MS
        }
    }

    /**
     * challenge=true: تحدي Cloudflare عادي (تهدئة قصيرة).
     * challenge=false: 403/429 من الخادم نفسه (حظر IP أو تجاوز الحد) ⇒ تهدئة طويلة.
     */
    fun penalize(challenge: Boolean) {
        penalties = (penalties + 1).coerceAtMost(4)
        streak = 0
        val minutes = if (challenge) {
            when (penalties) {
                1 -> 2L
                2 -> 10L
                3 -> 30L
                else -> 60L
            }
        } else {
            when (penalties) {
                1 -> 15L
                2 -> 30L
                3 -> 60L
                else -> 120L
            }
        }
        val wait = minutes * 60_000L
        val until = System.currentTimeMillis() + wait
        if (until > cooldownUntil) cooldownUntil = until
    }

    fun isCoolingDown(): Boolean = System.currentTimeMillis() < cooldownUntil

    fun cooldownRemaining(): Long =
        (cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    fun describe(): String =
        if (isCoolingDown()) "cooldown=${cooldownRemaining() / 1000}s"
        else "interval=${intervalMs}ms burst=${recent.size}"
}
