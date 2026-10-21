package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import nl.stockio.allocation.domain.model.AllocationCompletionResult
import nl.stockio.allocation.domain.model.AllocationRunResult
import nl.stockio.stock.infra.AllocationJobResultEntity
import nl.stockio.stock.infra.AllocationJobResultJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AllocationJobCompletionService(
    private val resultRepository: AllocationJobResultJpaRepository,
    private val objectMapper: ObjectMapper
) {

    @Transactional
    fun complete(
        jobId: UUID,
        allocationRun: AllocationRunResult
    ): AllocationCompletionResult {
        resultRepository.findById(jobId).orElse(null)?.let {
            return objectMapper.readValue(it.resultJson, AllocationCompletionResult::class.java)
        }

        val result = AllocationCompletionResult(allocationRun)
        resultRepository.save(
            AllocationJobResultEntity(
                jobId = jobId,
                resultJson = objectMapper.writeValueAsString(result)
            )
        )
        return result
    }
}
