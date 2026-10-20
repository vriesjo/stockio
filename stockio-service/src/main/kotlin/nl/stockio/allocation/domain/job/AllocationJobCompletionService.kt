package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import nl.stockio.allocation.domain.api.data.AllocationAndSyncResult
import nl.stockio.allocation.domain.api.data.AllocationRunResult
import nl.stockio.allocation.domain.api.data.ProcessDailyStockRequest
import nl.stockio.allocation.domain.processor.StockLedgerProcessor
import nl.stockio.allocation.domain.processor.StockProjectionProcessor
import nl.stockio.allocation.infra.AllocationJobResultEntity
import nl.stockio.allocation.infra.AllocationJobResultRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AllocationJobCompletionService(
    private val stockProjectionProcessor: StockProjectionProcessor,
    private val stockLedgerProcessor: StockLedgerProcessor,
    private val resultRepository: AllocationJobResultRepository,
    private val objectMapper: ObjectMapper
) {

    @Transactional
    fun complete(
        jobId: UUID,
        allocationRun: AllocationRunResult,
        request: ProcessDailyStockRequest
    ): AllocationAndSyncResult {
        resultRepository.findById(jobId).orElse(null)?.let {
            return objectMapper.readValue(it.resultJson, AllocationAndSyncResult::class.java)
        }

        stockProjectionProcessor.process(request.snapshotDate)
        val stockSync = stockLedgerProcessor.process(request)
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
