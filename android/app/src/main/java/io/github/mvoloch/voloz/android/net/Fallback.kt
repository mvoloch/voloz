package io.github.mvoloch.voloz.android.net

/**
 * Backup providers (port of the desktop's backend/services/providers.py): the
 * main provider gets the request first and each backup after a
 * [ProviderException]. A provider that answered HTTP 429 is skipped until the
 * wait it asked for (or [DEFAULT_COOLDOWN_MS]) has passed, so a spent free tier
 * doesn't cost a failed round trip on every dictation. When every provider is
 * cooling down they are all tried anyway, in order.
 */
class Fallback(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        const val DEFAULT_COOLDOWN_MS = 60_000L
        const val MAX_COOLDOWN_MS = 600_000L
    }

    /** (kind, endpoint, model) -> time (ms, [now]'s clock) until which it is skipped. */
    private val cooldownUntil = HashMap<Triple<String, String, String>, Long>()

    private fun identity(kind: String, p: Provider) = Triple(kind, p.baseUrl, p.model)

    /** [providers] without duplicates, the ones not cooling down first-come; all of them when none is ready. */
    @Synchronized
    fun order(kind: String, providers: List<Provider>): List<Provider> {
        val seen = HashSet<Triple<String, String, String>>()
        val candidates = providers.filter { seen.add(identity(kind, it)) }
        val t = now()
        val ready = candidates.filter { (cooldownUntil[identity(kind, it)] ?: 0L) <= t }
        return ready.ifEmpty { candidates }
    }

    /**
     * Runs [call] on each provider in [order] until one answers. Returns the provider
     * that answered, its result and how many failed before it. Other exceptions
     * (an empty or malformed answer) are thrown at once: another provider would get
     * the same input. When every provider fails, the last error is thrown, so a 429
     * still reads as a rate limit to the caller. Once [stop] says so, no further
     * provider is tried ([DictationCancelled]).
     */
    fun <T> run(kind: String, providers: List<Provider>, stop: () -> Boolean = { false }, call: (Provider) -> T): Answer<T> {
        require(providers.isNotEmpty()) { "no $kind provider configured" }
        var last: ProviderException? = null
        var failed = 0
        for (p in order(kind, providers)) {
            if (stop()) throw DictationCancelled()
            val result = try {
                call(p)
            } catch (e: ProviderException) {
                // A cancelled request fails like a network error: don't park or retry on it.
                if (stop()) throw DictationCancelled()
                last = e
                failed++
                if (e.status == 429) park(kind, p, e.retryAfterSeconds)
                continue
            }
            synchronized(this) { cooldownUntil.remove(identity(kind, p)) }
            return Answer(p, result, backup = !p.sameAs(providers.first()), failedBefore = failed)
        }
        throw checkNotNull(last)
    }

    @Synchronized
    private fun park(kind: String, p: Provider, retryAfterSeconds: Double?) {
        val wait = retryAfterSeconds?.let { (it * 1000).toLong() } ?: DEFAULT_COOLDOWN_MS
        cooldownUntil[identity(kind, p)] = now() + wait.coerceAtMost(MAX_COOLDOWN_MS)
    }

    /** Forget every cooldown (after the user changes providers). */
    @Synchronized
    fun reset() = cooldownUntil.clear()

    data class Answer<T>(val provider: Provider, val value: T, val backup: Boolean, val failedBefore: Int)
}
