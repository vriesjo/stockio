package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import nl.stockio.allocation.domain.bean.AllocationAndSyncResult
import nl.stockio.allocation.domain.bean.AllocationRequest
import nl.stockio.allocation.domain.bean.ProcessDailyStockRequest
import nl.stockio.allocation.infra.AllocationJobResultEntity
import nl.stockio.allocation.infra.AllocationJobResultRepository
import nl.stockio.allocation.domain.processor.StockLedgerProcessor
import nl.stockio.allocation.domain.service.AllocationService
import nl.stockio.allocation.domain.processor.StockProjectionProcessor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AllocationJob(
    private val allocationService: AllocationService,
    private val stockLedgerProcessor: StockLedgerProcessor,
    private val stockProjectionProcessor: StockProjectionProcessor,
    private val resultRepository: AllocationJobResultRepository,
    private val objectMapper: ObjectMapper
) {

    @Transactional
    fun execute(jobId: UUID, request: AllocationRequest): AllocationAndSyncResult {
        val allocationRun = allocationService.allocation(request)
        stockProjectionProcessor.process(request.asOfDate)
        val stockSync = stockLedgerProcessor.process(ProcessDailyStockRequest(request.asOfDate))
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
