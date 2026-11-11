package nl.stockio.allocation.domain.api

import jakarta.validation.Valid
import java.nio.file.Path
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.ExcelReportRequest
import nl.stockio.allocation.domain.service.AllocationJobRunrService
import nl.stockio.allocation.domain.tools.AllocationMcpTools
import nl.stockio.allocation.domain.service.AllocationExcelService
import nl.stockio.config.BlockingHttp
import org.springframework.beans.factory.annotation.Value
import reactor.core.publisher.Mono
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin")
class AllocationAdminController(
    private val allocationExcelService: AllocationExcelService,
    private val allocationTools: AllocationMcpTools,
    private val allocationJobRunrService: AllocationJobRunrService,
    @Value("\${stockio.allocation.csv-path}")
    private val csvPath: String,
    private val blockingHttp: BlockingHttp

) {

    @GetMapping("/allocations/generate-request-by-csv")
    fun loadCsv(): Mono<AllocationRequest> = blockingHttp.call {
        allocationExcelService.loadCsvAndCreateAllocationRequest(Path.of(csvPath))
    }

    @PostMapping("/allocations/generate-excel")
    fun generateExcelReport(
        @Valid @RequestBody request: ExcelReportRequest
    ): Mono<String> = blockingHttp.call {
        allocationTools.generateExcelReport(request)
    }

    @DeleteMapping("/allocations/flush-all-jobs")
    fun flushAllJobs(): Mono<String> = blockingHttp.call {
        allocationJobRunrService.flushAllJobs()
        "Jobs with excessive retries deleted."
    }

}
