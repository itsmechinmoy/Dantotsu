package ani.dantotsu.connections.anilist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Observable rate-limit status so UI can show a quiet indicator instead of spamming toasts. */
data class AnilistRateLimitState(
    val waiting: Boolean = false,
    val retryAtEpochMs: Long = 0L,
    val exhausted: Boolean = false,
)

/** Minimal HTTP response envelope passed through [AnilistRateLimiter]. */
data class AnilistHttpResponse(
    val code: Int,
    val text: String,
    val retryAfterSeconds: Int? = null,
    val rateLimitRemaining: Int? = null,
    val rateLimitResetRaw: Long? = null,
    val rateLimitLimit: Int? = null,
)

class AnilistRateLimitException(
    val waitSeconds: Long,
    message: String,
) : Exception(message)

/**
 * Returns true if the GraphQL document is a mutation (never retried, never coalesced, never cached).
 * Leading whitespace and `#` comment lines are ignored.
 */
fun isGraphqlMutation(query: String): Boolean {
    var i = 0
    val len = query.length
    while (i < len) {
        val c = query[i]
        when {
            c.isWhitespace() || c == ',' -> i++
            c == '#' -> {
                while (i < len && query[i] != '\n') i++
            }
            else -> break
        }
    }
    if (i >= len) return false
    val keyword = "mutation"
    if (i + keyword.length > len) return false
    if (!query.regionMatches(i, keyword, 0, keyword.length, ignoreCase = true)) return false
    val nextIdx = i + keyword.length
    if (nextIdx >= len) return true
    val next = query[nextIdx]
    return !(next.isLetterOrDigit() || next == '_')
}

/**
 * Normalizes an `X-RateLimit-Reset` header value to epoch milliseconds.
 * AniList sends epoch seconds, though some proxies/tests send milliseconds.
 */
internal fun normalizeResetEpochMs(raw: Long?): Long? {
    if (raw == null || raw <= 0L) return null
    return if (raw > 10_000_000_000L) raw else raw * 1000L
}

/**
 * Computes how long to wait after HTTP 429.
 * Priority: `Retry-After` (seconds) -> `X-RateLimit-Reset` -> bounded exponential backoff.
 */
internal fun computeRetryWaitMs(
    nowMs: Long,
    retryAfterSeconds: Int?,
    rateLimitResetRaw: Long?,
    attempt: Int,
    minRetryWaitMs: Long = 1_000L,
): Long {
    if (retryAfterSeconds != null && retryAfterSeconds > 0) {
        return max(minRetryWaitMs, retryAfterSeconds * 1000L)
    }
    val resetMs = normalizeResetEpochMs(rateLimitResetRaw)
    if (resetMs != null && resetMs > nowMs) {
        return max(minRetryWaitMs, resetMs - nowMs)
    }
    val shift = attempt.coerceIn(0, 4)
    val fallback = min(60_000L, 15_000L * (1L shl shift))
    return max(minRetryWaitMs, fallback)
}

private class GraphqlRootScanner {
    var depth = 0
    var parenDepth = 0
    var inString = false
    var escaped = false
    var inComment = false
    var rootFields = 0
    var expectFieldName = false

    fun feed(ch: Char) {
        if (handleCommentOrString(ch)) return
        when (ch) {
            '#' -> inComment = true
            '"' -> inString = true
            '(' -> if (depth == 1) parenDepth++
            ')' -> if (depth == 1 && parenDepth > 0) parenDepth--
            '{' -> onOpenBrace()
            '}' -> onCloseBrace()
            else -> handleRootTokenChar(ch)
        }
    }

    private fun handleCommentOrString(ch: Char): Boolean {
        if (inComment) {
            if (ch == '\n' || ch == '\r') inComment = false
            return true
        }
        if (!inString) return false
        when {
            escaped -> escaped = false
            ch == '\\' -> escaped = true
            ch == '"' -> inString = false
        }
        return true
    }

    private fun onOpenBrace() {
        depth++
        if (depth == 1) expectFieldName = true
        else if (depth == 2 && parenDepth == 0) expectFieldName = false
    }

    private fun onCloseBrace() {
        depth--
        if (depth == 1) expectFieldName = true
    }

    private fun handleRootTokenChar(ch: Char) {
        if (depth != 1 || parenDepth != 0) return
        if (ch == ':') {
            expectFieldName = false
        } else if (expectFieldName && (ch.isLetter() || ch == '_')) {
            rootFields++
            expectFieldName = false
        }
    }
}

/**
 * Estimates how many AniList rate-limit quota units [query] consumes by counting
 * top-level root fields/aliases inside the outer GraphQL selection set (Nov 2026
 * aliased query & `Page` rate-limit rules). Returns at least 1.
 */
internal fun estimateGraphqlOperationCount(query: String): Int {
    val scanner = GraphqlRootScanner()
    for (ch in query) scanner.feed(ch)
    return max(1, scanner.rootFields)
}

/**
 * Computes proactive pacing delay before dispatching the next AniList request based on
 * minimum request spacing and the most recently observed `X-RateLimit-Remaining` / `Reset`.
 */
internal fun computePacingDelayMs(
    nowMs: Long,
    remaining: Int?,
    resetAtMs: Long?,
    minIntervalMs: Long,
    lastDispatchMs: Long,
    weight: Int = 1,
): Long {
    val baseDelay = if (lastDispatchMs > 0L) {
        max(0L, lastDispatchMs + minIntervalMs - nowMs)
    } else {
        0L
    }
    if (remaining == null) return baseDelay
    // Ignore stale header counts once the reported window has already rolled over.
    if (resetAtMs != null && nowMs >= resetAtMs) return baseDelay

    val untilResetMs = if (resetAtMs != null && resetAtMs > nowMs) {
        (resetAtMs - nowMs).coerceAtMost(65_000L)
    } else {
        60_000L
    }

    if (remaining <= 0 || remaining < weight) {
        return max(baseDelay, untilResetMs)
    }

    val effectiveThreshold = max(5, weight + 3)
    val targetGapMs = when {
        remaining <= effectiveThreshold -> (untilResetMs / ((remaining / weight.coerceAtLeast(1)) + 1)).coerceIn(1_500L, 10_000L)
        remaining <= effectiveThreshold + 5 -> (untilResetMs / ((remaining / weight.coerceAtLeast(1)) + 1)).coerceIn(600L, 4_000L)
        else -> minIntervalMs
    }
    val adaptiveDelay = if (lastDispatchMs > 0L) {
        max(0L, lastDispatchMs + targetGapMs - nowMs)
    } else {
        0L
    }
    return max(baseDelay, adaptiveDelay)
}

/**
 * Single client-wide rate limiter for AniList GraphQL traffic:
 * - Caps in-flight concurrency (default 2) so startup/refresh never bursts.
 * - Tracks `X-RateLimit-Remaining` and `X-RateLimit-Reset` to slow down proactively.
 * - On HTTP 429, records the shared cooldown window, releases the concurrency slot while
 *   waiting for `Retry-After` / `X-RateLimit-Reset`, and retries read queries a bounded
 *   number of times. Mutations are NEVER retried.
 */
class AnilistRateLimiter(
    private val maxConcurrency: Int = 2,
    private val maxRetriesForReads: Int = 2,
    private val minIntervalMs: Long = 250L,
    private val minRetryWaitMs: Long = 1_000L,
    private val maxRetryWaitMs: Long = 95_000L,
    private val maxTotalWaitMs: Long = 120_000L,
    private val maxMutationWaitMs: Long = 5_000L,
    private val jitterMs: () -> Long = { Random.nextLong(200L, 600L) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val onBlockedUntilChanged: ((Long) -> Unit)? = null,
) {
    private val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
    private val stateMutex = Mutex()

    private val _state = MutableStateFlow(AnilistRateLimitState())
    val state: StateFlow<AnilistRateLimitState> = _state.asStateFlow()

    @Volatile
    private var internalBlockedUntilMs: Long = 0L

    @Volatile
    var externalBlockedUntilMs: Long = 0L

    @Volatile
    private var lastRemaining: Int? = null

    @Volatile
    private var lastLimit: Int? = null

    @Volatile
    private var resetAtMs: Long? = null

    private var lastDispatchMs: Long = 0L

    fun currentBlockedUntilMs(): Long = max(internalBlockedUntilMs, externalBlockedUntilMs)

    fun isCurrentlyBlocked(): Boolean = currentBlockedUntilMs() > nowMs()

    fun remainingSeconds(): Long {
        val diff = currentBlockedUntilMs() - nowMs()
        return if (diff > 0L) (diff + 999L) / 1000L else 0L
    }

    fun clearExternalGate() {
        externalBlockedUntilMs = 0L
        internalBlockedUntilMs = 0L
        _state.value = AnilistRateLimitState()
    }

    fun reset() {
        internalBlockedUntilMs = 0L
        externalBlockedUntilMs = 0L
        lastRemaining = null
        lastLimit = null
        resetAtMs = null
        lastDispatchMs = 0L
        _state.value = AnilistRateLimitState()
    }

    suspend fun execute(
        isMutation: Boolean,
        weight: Int = 1,
        call: suspend () -> AnilistHttpResponse,
    ): AnilistHttpResponse {
        var attempt = 0
        var totalWaitedMs = 0L
        val safeWeight = weight.coerceAtLeast(1)

        while (true) {
            val cooldownWaited = awaitSharedCooldownIfNeeded(isMutation, totalWaitedMs)
            if (cooldownWaited > 0L) {
                totalWaitedMs += cooldownWaited
                continue
            }

            when (val outcome = runAttemptUnderSemaphore(isMutation, safeWeight, attempt, totalWaitedMs, call)) {
                is AttemptOutcome.Done -> return outcome.response
                is AttemptOutcome.RetryAfter429 -> {
                    attempt++
                    totalWaitedMs = outcome.totalWaitedMs
                }
                is AttemptOutcome.BlockedByOther -> {
                    totalWaitedMs = outcome.totalWaitedMs
                }
                is AttemptOutcome.NeedsLongPacingWait -> {
                    totalWaitedMs = awaitLongPacingWait(isMutation, totalWaitedMs, outcome.waitMs)
                }
            }
        }
    }

    private sealed class AttemptOutcome {
        data class Done(val response: AnilistHttpResponse) : AttemptOutcome()
        data class RetryAfter429(val totalWaitedMs: Long) : AttemptOutcome()
        data class BlockedByOther(val totalWaitedMs: Long) : AttemptOutcome()
        data class NeedsLongPacingWait(val waitMs: Long) : AttemptOutcome()
    }

    private suspend fun awaitSharedCooldownIfNeeded(isMutation: Boolean, totalWaitedMs: Long): Long {
        val nowBefore = nowMs()
        val blockedUntil = currentBlockedUntilMs()
        if (blockedUntil <= nowBefore) return 0L

        val waitMs = blockedUntil - nowBefore
        val waitSec = (waitMs + 999L) / 1000L
        val tooLongForCaller = waitMs > maxRetryWaitMs ||
            (isMutation && waitMs > maxMutationWaitMs) ||
            totalWaitedMs + waitMs > maxTotalWaitMs
        if (tooLongForCaller) {
            if (waitMs <= 600_000L) {
                _state.value = AnilistRateLimitState(
                    waiting = false,
                    retryAtEpochMs = blockedUntil,
                    exhausted = true,
                )
            }
            throw AnilistRateLimitException(
                waitSeconds = waitSec,
                message = "Rate limited. Try after $waitSec seconds",
            )
        }
        _state.value = AnilistRateLimitState(
            waiting = true,
            retryAtEpochMs = blockedUntil,
            exhausted = false,
        )
        sleeper(waitMs)
        return waitMs
    }

    private suspend fun runAttemptUnderSemaphore(
        isMutation: Boolean,
        weight: Int,
        attempt: Int,
        totalWaitedMs: Long,
        call: suspend () -> AnilistHttpResponse,
    ): AttemptOutcome {
        semaphore.acquire()
        try {
            if (currentBlockedUntilMs() > nowMs()) {
                return AttemptOutcome.BlockedByOther(totalWaitedMs)
            }

            var shortSleepMs = 0L
            var longPacingSleepMs = 0L
            stateMutex.withLock {
                val nowInner = nowMs()
                val delayNeeded = computePacingDelayMs(
                    nowMs = nowInner,
                    remaining = lastRemaining,
                    resetAtMs = resetAtMs,
                    minIntervalMs = minIntervalMs,
                    lastDispatchMs = lastDispatchMs,
                    weight = weight,
                )
                if (delayNeeded > 1_500L) {
                    longPacingSleepMs = delayNeeded
                } else {
                    shortSleepMs = delayNeeded
                    lastDispatchMs = nowInner + delayNeeded
                    lastRemaining?.let { prev ->
                        if (resetAtMs == null || nowInner < resetAtMs!!) {
                            lastRemaining = (prev - weight).coerceAtLeast(0)
                        }
                    }
                }
            }

            if (longPacingSleepMs > 0L) {
                return AttemptOutcome.NeedsLongPacingWait(longPacingSleepMs)
            }

            var waited = totalWaitedMs
            if (shortSleepMs > 0L) {
                sleeper(shortSleepMs)
                waited += shortSleepMs
            }

            val response = call()
            recordHeaders(response)

            if (response.code == 429) {
                handle429Response(response, isMutation, attempt, waited)
                return AttemptOutcome.RetryAfter429(waited)
            }

            if (response.code in 200..299) {
                val current = _state.value
                if (current.waiting || current.exhausted) {
                    _state.value = AnilistRateLimitState()
                }
            }
            return AttemptOutcome.Done(response)
        } catch (e: CancellationException) {
            throw e
        } finally {
            semaphore.release()
        }
    }

    private fun handle429Response(
        response: AnilistHttpResponse,
        isMutation: Boolean,
        attempt: Int,
        totalWaitedMs: Long,
    ) {
        val nowAfter = nowMs()
        val baseWaitMs = computeRetryWaitMs(
            nowMs = nowAfter,
            retryAfterSeconds = response.retryAfterSeconds,
            rateLimitResetRaw = response.rateLimitResetRaw,
            attempt = attempt,
            minRetryWaitMs = minRetryWaitMs,
        )
        val jitter = if (baseWaitMs > maxRetryWaitMs) 0L else jitterMs().coerceAtLeast(0L)
        val waitMs = max(minRetryWaitMs, baseWaitMs + jitter)
        val newBlockedUntil = max(internalBlockedUntilMs, nowAfter + waitMs)
        internalBlockedUntilMs = newBlockedUntil
        onBlockedUntilChanged?.invoke(newBlockedUntil)

        val waitSec = (waitMs + 999L) / 1000L
        val canRetry = !isMutation &&
            attempt < maxRetriesForReads &&
            waitMs <= maxRetryWaitMs &&
            totalWaitedMs + waitMs <= maxTotalWaitMs

        if (!canRetry) {
            _state.value = AnilistRateLimitState(
                waiting = false,
                retryAtEpochMs = newBlockedUntil,
                exhausted = true,
            )
            throw AnilistRateLimitException(
                waitSeconds = waitSec,
                message = "Rate limited. Try after $waitSec seconds",
            )
        }

        _state.value = AnilistRateLimitState(
            waiting = true,
            retryAtEpochMs = newBlockedUntil,
            exhausted = false,
        )
    }

    private suspend fun awaitLongPacingWait(
        isMutation: Boolean,
        totalWaitedMs: Long,
        longPacingSleepMs: Long,
    ): Long {
        val waitSec = (longPacingSleepMs + 999L) / 1000L
        if (longPacingSleepMs > maxRetryWaitMs ||
            (isMutation && longPacingSleepMs > maxMutationWaitMs) ||
            totalWaitedMs + longPacingSleepMs > maxTotalWaitMs
        ) {
            throw AnilistRateLimitException(
                waitSeconds = waitSec,
                message = "Rate limited. Try after $waitSec seconds",
            )
        }
        if (longPacingSleepMs >= 2_500L) {
            _state.value = AnilistRateLimitState(
                waiting = true,
                retryAtEpochMs = nowMs() + longPacingSleepMs,
                exhausted = false,
            )
        }
        sleeper(longPacingSleepMs)
        val resetTarget = resetAtMs
        if (lastRemaining == 0 && (resetTarget == null || nowMs() >= resetTarget)) {
            lastRemaining = null
        }
        return totalWaitedMs + longPacingSleepMs
    }

    private fun recordHeaders(response: AnilistHttpResponse) {
        response.rateLimitLimit?.let { lastLimit = it }
        response.rateLimitRemaining?.let { lastRemaining = it }
        normalizeResetEpochMs(response.rateLimitResetRaw)?.let { resetAtMs = it }
    }
}
