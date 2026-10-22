package nl.stockio.allocation.domain.model

import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.api.dto.InboundShipment

enum class BackorderAllocationTrigger {
    MANUAL,
    STOCK_LEDGER_RECEIPT
}

data class BackorderAllocationRequest(
    val requestId: UUID,
    val asOfDate: LocalDate,
    val trigger: BackorderAllocationTrigger,
    val skuScope: Set<String>? = null,
    val inboundShipments: List<InboundShipment> = emptyList()
)
