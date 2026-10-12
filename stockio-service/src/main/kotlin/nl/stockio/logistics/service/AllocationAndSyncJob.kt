package nl.stockio.logistics.service

import com.fasterxml.jackson.databind.ObjectMapper
import nl.stockio.logistics.domain.AllocationAndSyncResult
import nl.stockio.logistics.domain.AllocationRequest
import nl.stockio.logistics.domain.ProcessDailyStockRequest
import nl.stockio.logistics.persistence.AllocationJobResultEntity
import nl.stockio.logistics.persistence.AllocationJobResultRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AllocationAndSyncJob(
    private val logisticsAllocationService: LogisticsAllocationService,
    private val dailyStockLedgerService: DailyStockLedgerService,
    private val stockProjectionService: StockProjectionService,
    private val resultRepository: AllocationJobResultRepository,
    private val objectMapper: ObjectMapper
) {

    @Transactional
    fun execute(jobId: UUID, request: AllocationRequest): AllocationAndSyncResult {
        val allocationRun = logisticsAllocationService.calculateAllocation(request)
        stockProjectionService.rebuild(request.asOfDate)
        val stockSync = dailyStockLedgerService.process(ProcessDailyStockRequest(request.asOfDate))
        val result = AllocationAndSyncResult(allocationRun, stockSync)
        resultRepository.save(
            AllocationJobResultEntity(
                jobId = jobId,
                resultJson = objectMapper.writeValueAsString(result)
            )
        )
        return result
    }
}
