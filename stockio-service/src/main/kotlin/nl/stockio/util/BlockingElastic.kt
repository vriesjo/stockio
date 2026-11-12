package nl.stockio.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.reactor.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Runs blocking Elastic work off the Netty event loop.
 *
 * The pool is larger than Hikari's default of 10 because a request holds its worker inside
 * `runBlocking` while SKU work on `Dispatchers.IO` still needs database connections.
 * Hikari and admission control remain the throughput limit.
 */
internal val blockingElastic: CoroutineDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
