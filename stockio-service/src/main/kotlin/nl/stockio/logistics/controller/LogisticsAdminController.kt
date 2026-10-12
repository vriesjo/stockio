package nl.stockio.logistics.controller

import jakarta.validation.Valid
import nl.stockio.logistics.domain.AllocationRequest
import nl.stockio.logistics.domain.ExcelReportRequest
import nl.stockio.logistics.mcp.LogisticsMcpTools
import nl.stockio.logistics.service.AllocationCsvLoader
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path

@RestController
@RequestMapping("/api/admin")
class LogisticsAdminController(
    private val allocationCsvLoader: AllocationCsvLoader,
    private val logisticsMcpTools: LogisticsMcpTools,
    @param:Value("\${logistics.allocation.csv-path}")
    private val csvPath: String
) {

    @PostMapping("/allocations/load-csv")
    fun loadCsv(): AllocationRequest =
        allocationCsvLoader.load(Path.of(csvPath))

    @PostMapping("/reports/excel")
    fun generateExcelReport(
        @Valid @RequestBody request: ExcelReportRequest
    ): String =
        logisticsMcpTools.generateExcelReport(request)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidRequest(exception: IllegalArgumentException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Invalid admin request."))
}
