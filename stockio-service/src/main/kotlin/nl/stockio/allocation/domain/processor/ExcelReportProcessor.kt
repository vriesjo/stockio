package nl.stockio.allocation.domain.processor

import java.nio.file.Files
import java.nio.file.Path
import nl.stockio.allocation.domain.api.data.AllocationRunResult
import nl.stockio.allocation.domain.api.data.AllocationSourceType
import org.apache.poi.ss.SpreadsheetVersion
import org.apache.poi.ss.usermodel.DataConsolidateFunction
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Service

@Service
class ExcelReportProcessor {

    fun process(run: AllocationRunResult, destination: Path): Path {
        Files.createDirectories(destination.toAbsolutePath().parent)

        XSSFWorkbook().use { workbook ->
            val detailsSheet = workbook.createSheet("Allocation Details")
            val detailHeaders = listOf(
                "Run ID",
                "Order ID",
                "Order Line ID",
                "Customer",
                "SKU",
                "Priority",
                "Placed Date",
                "Required Delivery Date",
                "Requested Quantity",
                "Allocated From Inventory",
                "Allocated From Inbound",
                "Backordered Quantity",
                "Status",
                "Processing Outcome",
                "Inventory Sources",
                "Inbound Sources"
            )
            writeHeader(detailsSheet, detailHeaders, workbook)

            run.allocations.forEachIndexed { index, record ->
                val row = detailsSheet.createRow(index + 1)
                val inventorySources = record.sources
                    .filter { it.sourceType == AllocationSourceType.INVENTORY }
                    .joinToString("; ") { "${it.sourceId}: ${it.quantity}" }
                val inboundSources = record.sources
                    .filter { it.sourceType == AllocationSourceType.INBOUND }
                    .joinToString("; ") {
                        "${it.shipmentId.orEmpty()}/${it.shipmentLineId ?: it.sourceId}: ${it.quantity}"
                    }
                val values = listOf(
                    run.runId.toString(),
                    record.orderId,
                    record.orderLineId,
                    record.customer,
                    record.sku,
                    record.priority,
                    record.placedDate.toString(),
                    record.requiredDeliveryDate.toString(),
                    record.requestedQuantity,
                    record.allocatedFromInventory,
                    record.allocatedFromInbound,
                    record.backorderedQuantity,
                    record.status?.name.orEmpty(),
                    record.processingOutcome.name,
                    inventorySources,
                    inboundSources
                )
                values.forEachIndexed { column, value -> writeCell(row, column, value) }
            }
            formatSheet(detailsSheet, detailHeaders.size)

            val sourceSheet = workbook.createSheet("Source Allocations")
            val sourceHeaders = listOf(
                "Run ID",
                "Order ID",
                "Order Line ID",
                "Customer",
                "SKU",
                "Source Type",
                "Source ID",
                "Shipment ID",
                "Shipment Line ID",
                "Quantity",
                "Source Date",
                "Warehouse Location"
            )
            writeHeader(sourceSheet, sourceHeaders, workbook)
            var sourceRowIndex = 1
            run.allocations.forEach { record ->
                record.sources.forEach { source ->
                    val row = sourceSheet.createRow(sourceRowIndex++)
                    val values = listOf(
                        run.runId.toString(),
                        record.orderId,
                        record.orderLineId,
                        record.customer,
                        record.sku,
                        source.sourceType.name,
                        source.sourceId,
                        source.shipmentId.orEmpty(),
                        source.shipmentLineId.orEmpty(),
                        source.quantity,
                        source.sourceDate.toString(),
                        source.warehouseLocation.orEmpty()
                    )
                    values.forEachIndexed { column, value -> writeCell(row, column, value) }
                }
            }
            formatSheet(sourceSheet, sourceHeaders.size)

            val pivotSheet = workbook.createSheet("Pivot Summary")
            if (run.allocations.isNotEmpty()) {
                val sourceRange = AreaReference(
                    "A1:P${run.allocations.size + 1}",
                    SpreadsheetVersion.EXCEL2007
                )
                val bySku = pivotSheet.createPivotTable(
                    sourceRange,
                    CellReference("A3"),
                    detailsSheet
                )
                bySku.addRowLabel(4)
                bySku.addRowLabel(12)
                bySku.addRowLabel(13)
                bySku.addColumnLabel(DataConsolidateFunction.SUM, 8, "Requested Quantity")
                bySku.addColumnLabel(DataConsolidateFunction.SUM, 9, "Allocated From Inventory")
                bySku.addColumnLabel(DataConsolidateFunction.SUM, 10, "Allocated From Inbound")
                bySku.addColumnLabel(DataConsolidateFunction.SUM, 11, "Backordered Quantity")

                val backordersByCustomer = pivotSheet.createPivotTable(
                    sourceRange,
                    CellReference("J3"),
                    detailsSheet
                )
                backordersByCustomer.addRowLabel(3)
                backordersByCustomer.addRowLabel(4)
                backordersByCustomer.addColumnLabel(
                    DataConsolidateFunction.SUM,
                    11,
                    "Backordered Quantity"
                )
            } else {
                pivotSheet.createRow(0).createCell(0).setCellValue("This run contains no allocation records.")
            }

            val formulaSummarySheet = workbook.createSheet("Formula Summary")
            val formulaSummaryHeaders = listOf(
                "SKU",
                "Status",
                "Processing Outcome",
                "Requested Quantity",
                "Allocated From Inventory",
                "Allocated From Inbound",
                "Backordered Quantity"
            )
            writeHeader(formulaSummarySheet, formulaSummaryHeaders, workbook)
            val summaryGroups = run.allocations
                .map { Triple(it.sku, it.status?.name.orEmpty(), it.processingOutcome.name) }
                .distinct()
                .sortedWith(
                    compareBy<Triple<String, String, String>> { it.first }
                        .thenBy { it.second }
                        .thenBy { it.third }
                )
            val sumColumns = listOf("I", "J", "K", "L")
            summaryGroups.forEachIndexed { index, (sku, status, outcome) ->
                val rowNumber = index + 2
                val row = formulaSummarySheet.createRow(index + 1)
                row.createCell(0).setCellValue(sku)
                row.createCell(1).setCellValue(status)
                row.createCell(2).setCellValue(outcome)
                sumColumns.forEachIndexed { columnIndex, sourceColumn ->
                    val sourceRange = "$sourceColumn:$sourceColumn"
                    row.createCell(columnIndex + 3).cellFormula =
                        "SUMIFS('Allocation Details'!$sourceRange,'Allocation Details'!E:E,A$rowNumber,'Allocation Details'!M:M,B$rowNumber,'Allocation Details'!N:N,C$rowNumber)"
                }
            }
            formatSheet(formulaSummarySheet, formulaSummaryHeaders.size)
            workbook.forceFormulaRecalculation = true

            Files.newOutputStream(destination).use { output ->
                workbook.write(output)
            }
        }
        return destination.toAbsolutePath()
    }

    private fun writeHeader(
        sheet: XSSFSheet,
        headers: List<String>,
        workbook: XSSFWorkbook
    ) {
        val style = workbook.createCellStyle()
        val font = workbook.createFont().apply { bold = true }
        style.setFont(font)
        val row = sheet.createRow(0)
        headers.forEachIndexed { column, header ->
            row.createCell(column).apply {
                setCellValue(header)
                cellStyle = style
            }
        }
    }

    private fun writeCell(row: Row, column: Int, value: Any) {
        val cell = row.createCell(column)
        when (value) {
            is Int -> cell.setCellValue(value.toDouble())
            is Long -> cell.setCellValue(value.toDouble())
            else -> cell.setCellValue(value.toString())
        }
    }

    private fun formatSheet(sheet: XSSFSheet, columnCount: Int) {
        sheet.createFreezePane(0, 1)
        repeat(columnCount) { column ->
            sheet.autoSizeColumn(column)
            sheet.setColumnWidth(column, minOf(sheet.getColumnWidth(column), 50 * 256))
        }
    }
}
