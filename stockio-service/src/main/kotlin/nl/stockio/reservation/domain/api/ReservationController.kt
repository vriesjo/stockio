package nl.stockio.reservation.domain.api

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.stockio.api.dto.OrderReservationResponse
import nl.stockio.api.dto.RedisKeyResponse
import nl.stockio.allocation.domain.fastpath.RedisKeys
import nl.stockio.allocation.domain.port.AllocationGateway
import nl.stockio.allocation.infra.AllocationRedisNotReadyException
import nl.stockio.allocation.infra.DisabledAllocationRedisGateway
import nl.stockio.reservation.domain.api.generated.ReservationApi
import nl.stockio.reservation.domain.service.OrderReservationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
class ReservationController(
    private val orderReservationService: OrderReservationService,
    private val allocationRedisGateway: AllocationGateway = DisabledAllocationRedisGateway()
) : ReservationApi {

    override fun getOrderReservationStatusByOrderId(orderId: String): ResponseEntity<OrderReservationResponse> =
        orderReservationService.getOrderReservationStatus(orderId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    override fun getOrderReservationStatusByRedisKey(key: String): ResponseEntity<RedisKeyResponse> {
        require(RedisKeys.isManaged(key)) {
            "Key must be a stock:sku, reservation, or expiry:res key."
        }
        if (!allocationRedisGateway.enabled) {
            throw AllocationRedisNotReadyException()
        }
        logger.info { "Reading Redis key $key" }
        return allocationRedisGateway.getByKey(key)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }
}
