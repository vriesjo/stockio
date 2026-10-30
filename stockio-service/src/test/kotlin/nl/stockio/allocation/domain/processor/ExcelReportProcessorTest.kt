package nl.stockio.allocation.domain.processor

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.AllocationProcessingOutcome
import nl.stockio.api.dto.AllocationRecord
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.api.dto.AllocationSource
import nl.stockio.api.dto.AllocationSourceType
import nl.stockio.api.dto.AllocationStatus
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExcelReportProcessorTest {

    @Test
    fun `report retains repeated document lines shipment provenance and skipped outcomes`() {
        val date = LocalDate.of(2026, 10, 21)
        val processed = AllocationRecord(
            orderId = "ORDER-1",
            orderLineId = "OL-1",
            customer = "Customer",
            sku = "MAKI-ZALM",
            priority = 1,
            placedDate = date,
            requiredDeliveryDate = date,
            requestedQuantity = 5,
            allocatedFromInventory = 0,
            allocatedFromInbound = 5,
            backorderedQuantity = 0,
            status = AllocationStatus.ALLOCATED,
            sources = listOf(
                AllocationSource(
                    sourceType = AllocationSourceType.INBOUND,
                    sourceId = "SL-1",
                    quantity = 3,
                    sourceDate = date,
                    shipmentId = "SHIP-1",
                    shipmentLineId = "SL-1"
                ),
                AllocationSource(
                    sourceType = AllocationSourceType.INBOUND,
                    sourceId = "SL-2",
                    quantity = 2,
                    sourceDate = date,
                    shipmentId = "SHIP-1",
                    shipmentLineId = "SL-2"
                )
            )
        )
        val skipped = processed.copy(
            orderLineId = "OL-2",
            allocatedFromInbound = 0,
            status = null,
            processingOutcome = AllocationProcessingOutcome.SKIPPED_ORDER_EXPIRED,
            sources = emptyList()
        )
        val run = AllocationRunResult(
            runId = UUID.randomUUID(),
            requestId = UUID.randomUUID(),
            runType = AllocationRunType.NEW_DEMAND,
            asOfDate = date,
            createdAt = Instant.now(),
            openBackorderCount = 0,
            allocations = listOf(processed, skipped)
        )
        val destination = Path.of("build", "excel-report-${UUID.randomUUID()}.xlsx")
        try {
            ExcelReportProcessor().process(run, destination)
            Files.newInputStream(destination).use { input ->
                XSSFWorkbook(input).use { workbook ->
                    val details = workbook.getSheet("Allocation Details")
                    assertEquals(2, details.lastRowNum)
                    assertEquals("Order Line ID", details.getRow(0).getCell(2).stringCellValue)
                    assertEquals("OL-1", details.getRow(1).getCell(2).stringCellValue)
                    assertEquals("OL-2", details.getRow(2).getCell(2).stringCellValue)
                    assertEquals("", details.getRow(2).getCell(12).stringCellValue)
                    assertEquals("SKIPPED_ORDER_EXPIRED", details.getRow(2).getCell(13).stringCellValue)
                    assertEquals("SHIP-1/SL-1: 3; SHIP-1/SL-2: 2", details.getRow(1).getCell(15).stringCellValue)

                    val sources = workbook.getSheet("Source Allocations")
                    assertEquals(2, sources.lastRowNum)
                    assertEquals("SHIP-1", sources.getRow(1).getCell(7).stringCellValue)
                    assertEquals("SL-1", sources.getRow(1).getCell(8).stringCellValue)
                    assertEquals("SL-2", sources.getRow(2).getCell(8).stringCellValue)
                    val summary = workbook.getSheet("Formula Summary")
                    val formula = summary.getRow(1).getCell(3).cellFormula
                    assertTrue(formula.contains("'Allocation Details'!I:I"))
                    assertTrue(formula.contains("'Allocation Details'!E:E"))
                    assertTrue(formula.contains("'Allocation Details'!M:M"))
                    assertTrue(formula.contains("'Allocation Details'!N:N"))
                    assertEquals(
                        "A1:P3",
                        workbook.getSheet("Pivot Summary").pivotTables.first()
                            .pivotCacheDefinition.getCTPivotCacheDefinition().cacheSource.worksheetSource.ref
                    )
                }
            }
        } finally {
            Files.deleteIfExists(destination)
        }
    }
}
