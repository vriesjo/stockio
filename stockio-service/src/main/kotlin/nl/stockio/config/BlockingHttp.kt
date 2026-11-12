package nl.stockio.config

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.reactor.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers

/**
 * Runs blocking HTTP work off the Netty event loop.
 *
 * The pool is larger than Hikari's default of 10 because status, Redis, and Kafka
 * acknowledgement still block a worker until they return. Inline allocation does not:
 * its SKU work uses the R2DBC pool. Hikari and admission control remain the throughput limit.
 */
class BlockingHttp(private val scheduler: Scheduler) {
    private val dispatcher: CoroutineDispatcher = scheduler.asCoroutineDispatcher()

    fun <T> call(action: () -> T): Mono<T> =
        Mono.fromCallable(action).subscribeOn(scheduler)

    suspend fun <T> await(action: () -> T): T = withContext(dispatcher) { action() }

    companion object {
        fun immediate(): BlockingHttp = BlockingHttp(Schedulers.immediate())
    }
}

@Configuration
class BlockingHttpConfiguration {
    @Bean(destroyMethod = "dispose")
    fun blockingHttpScheduler(): Scheduler {
        val threadCap = (Runtime.getRuntime().availableProcessors() * 10).coerceAtLeast(16)
        return Schedulers.newBoundedElastic(
            threadCap,
            Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
            "blocking-http"
        )
    }

    @Bean
    fun blockingHttp(blockingHttpScheduler: Scheduler): BlockingHttp = BlockingHttp(blockingHttpScheduler)
}
