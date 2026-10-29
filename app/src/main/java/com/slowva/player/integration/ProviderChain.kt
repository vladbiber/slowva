package com.slowva.player.integration

import com.slowva.player.diag.Attempt
import com.slowva.player.diag.Diagnostics
import com.slowva.player.diag.Failure
import com.slowva.player.diag.classOf
import com.slowva.player.net.NetworkBlockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A failure that carries the HTTP status, so diagnostics can tell 403 from 429 from 5xx. */
class HttpStatusException(val status: Int, message: String) : Exception(message)

/** One implementation behind a connection point, e.g. "Radio Browser" for the radio directory. */
class Provider<T>(val name: String, val component: String, val impl: T)

data class ProviderHealth(
    val successes: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    /** set after repeated failures; the provider is skipped until this time */
    val pausedUntil: Long = 0L,
)

/** What a connection point shows the UI: working, or greyed out with a reason. */
data class ChainStatus(val available: Boolean, val message: String? = null)

/**
 * A connection point: an ordered list of providers tried in turn. Every attempt is kept,
 * and when all fail the whole chain is filed with Diagnostics and the feature greys out
 * instead of breaking anything else.
 */
class ProviderChain<T>(
    private val feature: String,
    private val providers: List<Provider<T>>,
    private val pauseAfterFailures: Int = 5,
    private val pauseMs: Long = 10 * 60_000L,
    /** error class for the diagnostic filed when every provider failed */
    private val classify: (Throwable?, Int?) -> String = ::classOf,
    /** failures that end the chain without blaming the provider, e.g. a settings switch */
    private val stopOn: (Throwable) -> Boolean = { it is NetworkBlockedException },
) {
    private val _health = MutableStateFlow(providers.associate { it.name to ProviderHealth() })
    val health: StateFlow<Map<String, ProviderHealth>> = _health.asStateFlow()

    private val _status = MutableStateFlow(ChainStatus(available = true))
    val status: StateFlow<ChainStatus> = _status.asStateFlow()

    /** Off switch for the whole feature, e.g. from Settings. */
    @Volatile var enabled = true

    suspend fun <R> run(action: String, item: String? = null, block: suspend (T) -> R): Result<R> {
        if (!enabled) return Result.failure(IllegalStateException("$feature is switched off"))
        val attempts = ArrayList<Attempt>()
        var lastError: Throwable? = null
        val now = System.currentTimeMillis()
        for (p in providers) {
            if ((_health.value[p.name]?.pausedUntil ?: 0L) > now) {
                attempts += Attempt(p.name, "paused after repeated failures")
                continue
            }
            try {
                val r = block(p.impl)
                update(p.name) { it.copy(successes = it.successes + 1, consecutiveFailures = 0, lastError = null, pausedUntil = 0L) }
                _status.value = ChainStatus(available = true)
                return Result.success(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                attempts += Attempt(p.name, (e as? HttpStatusException)?.status?.toString() ?: e.message ?: e.javaClass.simpleName)
                // a switch in Settings isn't the provider's fault
                if (stopOn(e)) break
                update(p.name) {
                    val fails = it.consecutiveFailures + 1
                    it.copy(
                        consecutiveFailures = fails,
                        lastError = e.message,
                        pausedUntil = if (fails >= pauseAfterFailures) now + pauseMs else 0L,
                    )
                }
            }
        }
        val status = (lastError as? HttpStatusException)?.status
        Diagnostics.record(
            Failure(
                feature = feature,
                action = action,
                component = providers.joinToString(" + ") { it.component },
                item = item,
                attempts = attempts,
                httpStatus = status,
                error = lastError,
                errorClass = classify(lastError, status),
            )
        )
        _status.value = ChainStatus(
            available = false,
            message = (lastError as? NetworkBlockedException)?.message ?: "$feature isn't working right now",
        )
        return Result.failure(lastError ?: IllegalStateException("No provider for $feature"))
    }

    private fun update(name: String, f: (ProviderHealth) -> ProviderHealth) {
        val map = _health.value
        _health.value = map + (name to f(map[name] ?: ProviderHealth()))
    }
}
