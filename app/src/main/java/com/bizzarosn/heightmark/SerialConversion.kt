package com.bizzarosn.heightmark

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs a slow conversion over a stream of items one at a time, in the order
 * they arrive, and hands each result to [deliver].
 *
 * One consumer coroutine drains the inbox, so two conversions never overlap and
 * results never reorder. A lock inside [convert] could not give that guarantee:
 * a JVM monitor does not wake its waiters first-come-first-served.
 *
 * The inbox keeps only the newest unconverted item. While a conversion runs,
 * each arrival replaces the one before it, because for a stream of readings the
 * newest supersedes the rest and a backlog would only delay it. Once
 * conversions are fast nothing is ever waiting, so nothing is dropped.
 *
 * [convert] may switch dispatchers; [deliver] always runs in the coroutine
 * [start] launched, so a caller that starts it on the main thread can keep
 * everything it mutates there. Not thread-safe: [start], [submit] and [stop]
 * belong to one thread.
 */
class SerialConversion<T : Any, R>(
    private val convert: suspend (T) -> R,
    private val deliver: (item: T, result: R) -> Unit
) {

    private var inbox: Channel<T>? = null
    private var consumer: Job? = null

    /** Starts the consumer in [scope]. A no-op while one is already running. */
    fun start(scope: CoroutineScope) {
        if (inbox != null) return
        val channel = Channel<T>(Channel.CONFLATED)
        inbox = channel
        consumer = scope.launch {
            for (item in channel) {
                val result = convert(item)
                // convert may finish after stop() cancelled the consumer: its
                // result is then stale, and delivering it would commit a fix the
                // session has already forgotten and re-arm the fix watchdog from
                // a screen the user has left
                if (!isActive) return@launch
                deliver(item, result)
            }
        }
    }

    /** Queues [item] behind the running conversion; dropped if [start] has not run. */
    fun submit(item: T) {
        inbox?.trySend(item)
    }

    /**
     * Discards the waiting item and the result of any conversion in flight.
     * A blocking [convert] cannot be interrupted, so it runs to completion on
     * its own thread, but its result never reaches [deliver].
     */
    fun stop() {
        inbox?.cancel()
        consumer?.cancel()
        inbox = null
        consumer = null
    }
}
