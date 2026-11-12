package nl.stockio.allocation.domain.port

import java.time.Instant
import java.util.UUID
import nl.stockio.allocation.domain.model.AllocationCalculationResult
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.AllocationSkuOperationEntity
import nl.stockio.allocation.infra.DemandOrderLineEntity
import nl.stockio.allocation.infra.OpenBackorderEntity
import nl.stockio.api.dto.AllocationRecord
import nl.stockio.reservation.infra.InboundReservationEntity
import nl.stockio.reservation.infra.InboundSupplyEntity
import nl.stockio.stock.infra.StockEntity
import nl.stockio.stock.infra.StockReservationEntity
import reactor.core.publisher.Mono

/**
 * Reads and writes the rows owned by one SKU allocation transaction.
 * Callers run these publishers on the connection [org.springframework.transaction.reactive.TransactionalOperator] put in the Reactor context.
 */
interface SkuAllocationDatabase {
    fun lockSku(sku: String): Mono<Void>
    fun findOperation(runId: UUID, sku: String): Mono<AllocationSkuOperationEntity>

    /**
     * Empty when the run is missing or still open, so the caller calculates.
     * A completed run emits its records for [sku], including an empty list.
     */
    fun completedAllocations(runId: UUID, sku: String): Mono<List<AllocationRecord>>
    fun findClaims(orderIds: Collection<String>): Mono<List<AllocationOrderClaimEntity>>
    fun findLines(lineIds: Collection<String>): Mono<List<DemandOrderLineEntity>>
    fun lockUnreceivedSupply(sku: String): Mono<List<InboundSupplyEntity>>
    fun lockStock(sku: String): Mono<List<StockEntity>>
    fun lockStockReservations(lineIds: Collection<String>): Mono<List<StockReservationEntity>>
    fun lockInboundReservations(lineIds: Collection<String>): Mono<List<InboundReservationEntity>>
    fun lockBackorders(sku: String): Mono<List<OpenBackorderEntity>>
    fun saveCalculation(calculation: AllocationCalculationResult, now: Instant): Mono<Void>
    fun deleteBackorder(orderLineId: String): Mono<Void>
    fun updateBackorderQuantity(orderLineId: String, remainingQuantity: Int, updatedAt: Instant): Mono<Void>
    fun saveOperation(runId: UUID, sku: String, requestHash: String, resultJson: String): Mono<Void>
}
