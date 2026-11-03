package nl.stockio.stock.domain.processor

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.ProcessDailyStockRequest
import nl.stockio.api.dto.StockSyncResult
import nl.stockio.reservation.domain.service.ReservationCleanupService
import nl.stockio.stock.domain.job.StockReceiptFollowupDispatcher
import nl.stockio.stock.domain.port.StockLedgerRunRepository
import nl.stockio.stock.domain.port.StockLedgerBusyException
import nl.stockio.stock.domain.port.StockLedgerRun
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class StockLedgerProcessor(
    private val runRepository: StockLedgerRunRepository,
    private val worker: StockLedgerSkuWorker,
    private val cleanup: ReservationCleanupService,
    private val followups: StockReceiptFollowupDispatcher,
    private val clock: Clock
) {
    @Transactional(propagation = Propagation.NEVER)
    fun process(request: ProcessDailyStockRequest): StockSyncResult {
        val today = LocalDate.now(clock)
        require(request.snapshotDate == null || request.snapshotDate == today) {
            "Only today's stock snapshot can be synchronized to live stock."
        }
        val owner = UUID.randomUUID()
        val run = begin(today, owner)
        val startedAt = System.nanoTime()
        try {
            run.skus.forEach { sku ->
                var attempts = 0
                while (true) {
                    try {
                        worker.process(run, owner, sku)
                        break
                    } catch (exception: LedgerCleanupRequiredException) {
                        if (++attempts > 3) throw exception
                        cleanup.expireOrders(exception.orderIds)
                    }
                }
            }
            require(LocalDate.now(clock) == run.snapshotDate) {
                "Ledger run crossed the business-date boundary; retry today's run."
            }
            val result = runRepository.complete(run.id, owner, clock.instant())
            logger.info {
                "Ledger run ${run.id} completed: date=${run.snapshotDate} skus=${result.skuCount} " +
                    "lots=${result.lotCount} elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}"
            }
            followups.dispatch()
            return result
        } catch (exception: Exception) {
            runRepository.fail(run.id, owner, exception.message ?: exception.javaClass.simpleName, clock.instant())
            throw exception
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    fun processToday(): StockSyncResult = process(ProcessDailyStockRequest())

    private fun begin(date: LocalDate, owner: UUID): StockLedgerRun {
        while (true) {
            require(LocalDate.now(clock) == date) { "Ledger request crossed the business-date boundary; retry today's run." }
            try {
                return runRepository.begin(date, owner, clock.instant())
            } catch (_: StockLedgerBusyException) {
                // Wait without holding a transaction or a pooled database connection.
                Thread.sleep(100)
            }

        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
