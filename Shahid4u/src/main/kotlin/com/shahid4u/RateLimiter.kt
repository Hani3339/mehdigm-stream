package com.shahid4u

import android.content.Context
import android.content.SharedPreferences
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
 * بوابة واحدة تمرّ عبرها كل طلبات shaahidd4u.net:
 *
 *  - طلب واحد فقط في كل مرة (Mutex) — لا توازٍ إطلاقاً.
 *  - فاصل 2.2s بين بدايات الطلبات (وليس بعد انتهائها).
 *  - نافذتان صارمتان: 18 طلباً/دقيقة و50 طلباً/10 دقائق.
 *  - عند الحظر/التحدي/429: توقف تصاعدي مشترك بين الجلسات (SharedPreferences)
 *    حتى لا يعود التطبيق للطرق بعد إعادة التشغيل.
 *
 * السبب الذي أثبتته صفحات الموقع المؤرشفة: Cloudflare Bot Management
 * + canary مخفي في كل صفحة. سحب كل المواسم دفعة واحدة (Supernatural = 15+)
 * كان يفعّل الحظر. هذه البوابة تجعل الطلبات بطيئة بما يكفي ولا تسمح بانفجار.
 */
object SiteGate {
    private const val MIN_INTERVAL_MS = 1_800L
    private const val SLOW_INTERVAL_MS = 4_000L
    private const val MAX_BLOCKING_WAIT_MS = 4_000L
    private const val MAX_WINDOW_WAIT_MS = 20_000L

    private const val WINDOW_MS = 60_000L
    private const val WINDOW_LIMIT = 24
    private const val LONG_WINDOW_MS = 10 * 60_000L
    private const val LONG_WINDOW_LIMIT = 65

    private const val SUCCESS_BEFORE_RELAX = 12
    private const val PREFS = "shahid4u_gate_v1"

    private val mutex = Mutex()
    private val recent = ArrayDeque<Long>()
    private val recentLong = ArrayDeque<Long>()

    @Volatile private var earliestAt = 0L
    @Volatile private var cooldownUntil = 0L
    @Volatile private var penalty = 0
    @Volatile private var streak = 0
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        cooldownUntil = p.getLong("cooldownUntil", 0L)
        penalty = p.getInt("penalty", 0)
        earliestAt = p.getLong("earliestAt", 0L)
    }

    private fun persist() {
        prefs?.edit()
            ?.putLong("cooldownUntil", cooldownUntil)
            ?.putInt("penalty", penalty)
            ?.putLong("earliestAt", earliestAt)
            ?.apply()
    }

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        var now = System.currentTimeMillis()

        val cooldownWait = cooldownUntil - now
        if (cooldownWait > 0) {
            if (cooldownWait > MAX_BLOCKING_WAIT_MS) throw SiteCooldownException(cooldownWait)
            delay(cooldownWait)
        }

        // نافذتان انزلاقيتان: قصيرة (دقيقة) وطويلة (10 دقائق).
        while (true) {
            now = System.currentTimeMillis()
            trimWindows(now)
            val waitShort = if (recent.size >= WINDOW_LIMIT) {
                recent.first() + WINDOW_MS - now
            } else 0L
            val waitLong = if (recentLong.size >= LONG_WINDOW_LIMIT) {
                recentLong.first() + LONG_WINDOW_MS - now
            } else 0L
            val wait = maxOf(waitShort, waitLong)
            if (wait <= 0L) break
            if (wait > MAX_WINDOW_WAIT_MS) {
                // لا نعلّق التطبيق 10 دقائق: تهدئة قصيرة ثم فشل سريع.
                extend(5 * 60_000L)
                throw SiteCooldownException(cooldownRemaining())
            }
            delay(wait.coerceAtLeast(100L))
        }

        val spacing = if (recent.size >= WINDOW_LIMIT - 2) SLOW_INTERVAL_MS else MIN_INTERVAL_MS
        val wait = earliestAt - System.currentTimeMillis()
        if (wait > 0L) delay(wait)

        val started = System.currentTimeMillis()
        recent.addLast(started)
        recentLong.addLast(started)
        earliestAt = started + spacing + Random.nextLong(0L, 500L)
        persist()

        block().also { onSuccess() }
    }

    private fun trimWindows(now: Long) {
        while (recent.isNotEmpty() && now - recent.first() >= WINDOW_MS) {
            recent.removeFirst()
        }
        while (recentLong.isNotEmpty() && now - recentLong.first() >= LONG_WINDOW_MS) {
            recentLong.removeFirst()
        }
    }

    private fun onSuccess() {
        streak++
        if (streak >= SUCCESS_BEFORE_RELAX) {
            streak = 0
            penalty = 0
            persist()
        }
    }

    /** تحدي Cloudflare عادي: الموقع ما زال يعمل، لكنه يطلب تحققاً. */
    fun penalizeChallenge() {
        penalty = (penalty + 1).coerceAtMost(5)
        streak = 0
        val minutes = when (penalty) {
            1 -> 10L
            2 -> 30L
            3 -> 120L
            4 -> 360L
            else -> 720L
        }
        extend(minutes * 60_000L)
    }

    /**
     * صفحة "Sorry, you have been blocked" أو "Attention Required" — حظر IP
     * على مستوى Cloudflare WAF. المحاولة أثناءه تطيله، لذلك 24 ساعة كاملة.
     */
    fun penalizeBlocked() {
        penalty = (penalty + 1).coerceAtMost(5)
        streak = 0
        val minutes = when (penalty) {
            1 -> 1_440L
            2 -> 2_880L
            else -> 4_320L
        }
        extend(minutes * 60_000L)
    }

    /** 429 أو تجاوز حد صريح؛ نحترم Retry-After إن وُجد. */
    fun penalizeRateLimit(retryAfterSeconds: Long? = null) {
        penalty = (penalty + 1).coerceAtMost(5)
        streak = 0
        val minutes = retryAfterSeconds?.let { (it / 60L).coerceAtLeast(5L) } ?: when (penalty) {
            1 -> 30L
            2 -> 120L
            3 -> 360L
            else -> 1_440L
        }
        extend(minutes * 60_000L)
    }

    /** توافق مع الاستدعاءات القديمة. */
    fun penalize(challenge: Boolean) {
        if (challenge) penalizeChallenge() else penalizeBlocked()
    }

    private fun extend(waitMs: Long) {
        val until = System.currentTimeMillis() + waitMs
        if (until > cooldownUntil) cooldownUntil = until
        persist()
    }

    fun isCoolingDown(): Boolean = System.currentTimeMillis() < cooldownUntil

    fun cooldownRemaining(): Long =
        (cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    fun describe(): String =
        if (isCoolingDown()) {
            "cooldown=${cooldownRemaining() / 1000}s penalty=$penalty"
        } else {
            "interval=${MIN_INTERVAL_MS}ms window=${recent.size}/$WINDOW_LIMIT " +
                    "long=${recentLong.size}/$LONG_WINDOW_LIMIT"
        }
}
