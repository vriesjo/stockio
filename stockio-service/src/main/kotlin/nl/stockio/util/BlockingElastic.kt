package nl.stockio.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.reactor.asCoroutineDispatcher
import reactor.core.scheduler.Schedulers

/** Blocking JDBC and Redis work for allocation. The shared Reactor pool, not a new one. */
internal val blockingElastic: CoroutineDispatcher = Schedulers.boundedElastic().asCoroutineDispatcher()
