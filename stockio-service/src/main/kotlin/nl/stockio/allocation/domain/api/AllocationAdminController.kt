package nl.stockio.allocation.domain.api

import jakarta.validation.Valid
import java.nio.file.Path
import nl.stockio.allocation.domain.bean.AllocationRequest
import nl.stockio.allocation.domain.bean.ExcelReportRequest
import nl.stockio.allocation.tools.AllocationTools
import nl.stockio.allocation.domain.service.AllocationExcelService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin")
class AllocationAdminController(
    private val allocationExcelService: AllocationExcelService,
    private val allocationTools: AllocationTools,
    @Value("\${stockio.allocation.csv-path}")
    private val csvPath: String
) {

    @GetMapping("/allocations/generate-request-by-csv")
    fun loadCsv(): AllocationRequest =
        allocationExcelService.loadCsvAndCreateAllocationRequest(Path.of(csvPath))

    @PostMapping("/allocations/generate-excel")
    fun generateExcelReport(
        @Valid @RequestBody request: ExcelReportRequest
    ): String =
        allocationTools.generateExcelReport(request)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidRequest(exception: IllegalArgumentException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Invalid admin request."))
}
