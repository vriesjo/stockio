package nl.stockio.stock.api

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.stockio.stock.domain.service.StockLedgerSyncStatus
import nl.stockio.stock.domain.service.StockLedgerService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/stock")
class StockController(
    private val ledgerSyncStatusService: StockLedgerService
) {

    @GetMapping("/ledger/status")
    fun stockLedgerStatus(): ResponseEntity<StockLedgerSyncStatus?> {
        logger.info { "Fetching stock ledger sync status, last sync run" }
        return ResponseEntity.ok()
            .body(ledgerSyncStatusService.getStatusLastSyncRun())
    }

}
