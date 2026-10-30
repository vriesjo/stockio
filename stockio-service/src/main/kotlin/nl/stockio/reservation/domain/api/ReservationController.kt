package nl.stockio.reservation.domain.api

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.stockio.api.dto.OrderReservationResponse
import nl.stockio.reservation.domain.api.generated.ReservationApi
import nl.stockio.reservation.domain.service.OrderReservationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
class ReservationController(
    private val orderReservationService: OrderReservationService
) : ReservationApi {

    override fun getOrderReservationStatus(orderId: String): ResponseEntity<OrderReservationResponse> =
        orderReservationService.getOrderReservationStatus(orderId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
}
