package nl.stockio.allocation.domain.fastpath

import java.util.UUID
import nl.stockio.api.dto.AllocationRequest

data class AllocationGatewayRedisHoldLine(
    val requestId: UUID,
    val orderId: String,
    val orderLineId: String,
    val sku: String,
    val warehouse: String,
    val qty: Int
)

object RedisKeys {
    fun stock(sku: String, warehouse: String): String = "stock:sku:$sku:$warehouse"

    fun reservation(line: AllocationGatewayRedisHoldLine): String =
        "reservation:${line.requestId}:${line.orderId}:${line.orderLineId}"

    fun expiry(line: AllocationGatewayRedisHoldLine): String =
        "expiry:res:${line.requestId}:${line.orderId}:${line.orderLineId}"

    fun isManaged(key: String): Boolean =
        key.startsWith("stock:sku:") || key.startsWith("reservation:") || key.startsWith("expiry:res:")

    fun lines(request: AllocationRequest, warehouse: String): List<AllocationGatewayRedisHoldLine> =
        request.demandOrders.flatMap { order ->
            order.lines.map { line ->
                require(!order.orderId.contains(':') && !line.orderLineId.contains(':')) {
                    "Redis ids cannot contain ':'."
                }
                AllocationGatewayRedisHoldLine(
                    requestId = request.requestId,
                    orderId = order.orderId,
                    orderLineId = line.orderLineId,
                    sku = line.sku,
                    warehouse = warehouse,
                    qty = line.requestedQuantity
                )
            }
        }
}
