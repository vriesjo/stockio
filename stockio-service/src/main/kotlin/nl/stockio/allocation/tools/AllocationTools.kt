package nl.stockio.allocation.tools

import java.nio.file.Path
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.ExcelReportRequest
import nl.stockio.api.dto.ProcessDailyStockRequest
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.stock.domain.processor.StockLedgerProcessor
import nl.stockio.allocation.domain.processor.ExcelReportProcessor
import nl.stockio.allocation.domain.service.AllocationRunService
import nl.stockio.allocation.domain.service.AllocationRequestLimits
import nl.stockio.allocation.domain.processor.AllocationProcessor
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.model.BackorderAllocationTrigger
import nl.stockio.reservation.domain.service.OrderReservationService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class AllocationTools(
    private val processingService: AllocationProcessor,
    private val allocationRunService: AllocationRunService,
    private val stockLedgerProcessor: StockLedgerProcessor,
    private val excelReportProcessor: ExcelReportProcessor,
    private val orderReservationService: OrderReservationService,
    private val requestLimits: AllocationRequestLimits,
    @param:Value("\${stockio.report.directory}")
    private val reportDirectory: String
) {

    @Tool(
        name = "allocation_and_stock_projection",
        description = "Allocate structured demand against inventory and inbound shipments. " +
            "Orders are processed by priority (lower number first), then FIFO. " +
            "Inbound shipments only cover orders when their expected arrival is on or before the required delivery date. " +
            "Any remainder is saved as an open backorder. New orders are unconfirmed temporary reservations " +
            "that expire unless confirmed. Rebuild daily stock projections without synchronizing physical stock."
    )
    fun allocateAndStockProject(request: AllocationRequest) {
        requestLimits.validate(request)
        require(request.runType == AllocationRunType.NEW_DEMAND) { "Run type must be NEW_DEMAND for allocation." }
        processingService.process(request)
    }

    @Tool(
        name = "allocate_open_backorders",
        description = "Use currently available inventory and shipments that have arrived by asOfDate " +
            "and whose expected arrival is on or before each order's required delivery date to fulfill persisted open backorders " +
            "in priority/FIFO order. Expired orders are excluded and temporary reservation deadlines are not renewed. " +
            "Rebuild daily projections without synchronizing physical stock."
    )
    fun allocateOpenBackordersByInventoryAndInboundSupply(request: AllocationRequest): AllocationRunResult {
        requestLimits.validate(request)
        require(request.runType == AllocationRunType.BACKORDER_FULFILLMENT) {
            "Run type must be BACKORDER_FULFILLMENT for backorder allocation."
        }
        return processingService.process(
            BackorderAllocationRequest(
                requestId = request.requestId,
                asOfDate = request.asOfDate,
                trigger = BackorderAllocationTrigger.MANUAL,
                inboundShipments = request.inboundShipments
            )
        )
    }

    @Tool(
        name = "process_stock_ledger",
        description = "Expire overdue reservations, rebuild today's stock projection, and synchronize it to live stock. " +
            "Only confirmed orders are delivered. This tool does not recalculate allocation or accept stock movements."
    )
    fun processStockLedger(request: ProcessDailyStockRequest) =
        stockLedgerProcessor.process(request)

    @Tool(
        name = "get_order_reservation_status",
        description = "Read an order's current reservation lifecycle and expiry deadline. " +
            "Saved allocation runs are historical and do not indicate whether a reservation is still active."
    )
    fun getOrderReservationStatus(orderId: String) =
        requireNotNull(orderReservationService.getOrderReservationStatus(orderId)) {
            "Order '$orderId' has no allocation reservation."
        }

    @Tool(
        name = "generate_excel_report",
        description = "Generate an Excel workbook with allocation details, source allocations, native pivot tables, and formula-based SKU/status summaries for a saved allocation run. Use a simple .xlsx file name; by default, the workbook is written under ~/itzitzo-mcp/reports."
    )
    fun generateExcelReport(request: ExcelReportRequest): String {
        require(request.fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.xlsx"))) {
            "fileName must be a simple .xlsx file name without a directory path."
        }
        val run = allocationRunService.getRun(request.runId)
        val path = excelReportProcessor.process(run, Path.of(reportDirectory).resolve(request.fileName))
        return path.toString()
    }
}
