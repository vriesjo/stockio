package nl.stockio.logistics.service

import nl.stockio.logistics.domain.AllocationAndSyncResult
import nl.stockio.logistics.domain.AllocationRequest
import nl.stockio.logistics.domain.ProcessDailyStockRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class LogisticsOrchestrator(
    private val logisticsAllocationService: LogisticsAllocationService,
    private val dailyStockLedgerService: DailyStockLedgerService
) {

    @Transactional
    fun calculateAllocationAndSync(request: AllocationRequest): AllocationAndSyncResult {
        require(request.asOfDate == LocalDate.now()) {
            "Allocation and stock synchronization require asOfDate to be today."
        }

        val allocationRun = logisticsAllocationService.calculateAllocation(request)
        val stockSync = dailyStockLedgerService.process(ProcessDailyStockRequest(request.asOfDate))
        return AllocationAndSyncResult(
            allocationRun = allocationRun,
            stockSync = stockSync
        )
    }
}
