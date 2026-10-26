package nl.stockio.reservation.domain.api

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.stockio.allocation.domain.api.dto.OrderReservationResponse
import nl.stockio.reservation.domain.service.OrderReservationService
import nl.stockio.allocation.domain.service.AllocationJobRunrService
import nl.stockio.allocation.domain.job.AllocationJobExecutor
import nl.stockio.stock.domain.service.StockLedgerSyncStatus
import nl.stockio.stock.domain.service.StockLedgerService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/reservations")
class ReservationController(
    private val orderReservationService: OrderReservationService
) {

    @GetMapping("/orders/{orderId}/status")
    fun orderReservationStatus(@PathVariable orderId: String): ResponseEntity<OrderReservationResponse> =
        orderReservationService.getOrderReservationStatus(orderId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
}
