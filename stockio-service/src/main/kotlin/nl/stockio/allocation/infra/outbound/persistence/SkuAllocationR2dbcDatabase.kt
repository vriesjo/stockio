package nl.stockio.allocation.infra

import io.r2dbc.spi.Row
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import nl.stockio.allocation.domain.model.AllocationCalculationResult
import nl.stockio.allocation.domain.port.outbound.SkuAllocationDatabase
import nl.stockio.api.dto.AllocationProcessingOutcome
import nl.stockio.api.dto.AllocationRecord
import nl.stockio.api.dto.AllocationSource
import nl.stockio.api.dto.AllocationSourceType
import nl.stockio.api.dto.AllocationStatus
import nl.stockio.api.dto.OrderReservationStatus
import nl.stockio.reservation.infra.InboundReservationEntity
import nl.stockio.reservation.infra.InboundSupplyEntity
import nl.stockio.stock.infra.StockEntity
import nl.stockio.stock.infra.StockReservationEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
class SkuAllocationR2dbcDatabase(
    private val databaseClient: DatabaseClient
) : SkuAllocationDatabase {

    override fun lockSku(sku: String): Mono<Void> =
        databaseClient.sql("INSERT INTO allocation_sku_lock (sku) VALUES (:sku) ON CONFLICT (sku) DO NOTHING")
            .bind("sku", sku)
            .then()
            .then(
                databaseClient.sql("SELECT sku FROM allocation_sku_lock WHERE sku = :sku FOR UPDATE")
                    .bind("sku", sku)
                    .map { row, _ -> row.text("sku") }
                    .all()
                    .collectList()
                    .flatMap { rows ->
                        if (rows.singleOrNull() == null) {
                            Mono.error(IllegalStateException("Could not acquire allocation lock for SKU '$sku'."))
                        } else {
                            Mono.empty()
                        }
                    }
            )

    override fun findOperation(runId: UUID, sku: String): Mono<AllocationSkuOperationEntity> =
        databaseClient.sql(
            """
            SELECT id, run_id, sku, request_hash, result_json
            FROM allocation_sku_operation
            WHERE run_id = :runId AND sku = :sku
            """.trimIndent()
        )
            .bind("runId", runId)
            .bind("sku", sku)
            .map { row, _ ->
                AllocationSkuOperationEntity(
                    id = row.uuid("id"),
                    runId = row.uuid("run_id"),
                    sku = row.text("sku"),
                    requestHash = row.text("request_hash"),
                    resultJson = row.text("result_json")
                )
            }
            .all()
            .collectList()
            .flatMap { rows ->
                when (rows.size) {
                    0 -> Mono.empty()
                    1 -> Mono.just(rows.single())
                    else -> Mono.error(IllegalStateException("Expected one SKU operation for '$sku'."))
                }
            }

    override fun completedAllocations(runId: UUID, sku: String): Mono<List<AllocationRecord>> =
        databaseClient.sql("SELECT completed FROM allocation_run WHERE id = :id")
            .bind("id", runId)
            .map { row, _ -> row.bool("completed") }
            .all()
            .collectList()
            .flatMap { rows ->
                val completed = rows.singleOrNull() ?: return@flatMap Mono.empty()
                if (!completed) Mono.empty() else loadRecords(runId, sku)
            }

    override fun findClaims(orderIds: Collection<String>): Mono<List<AllocationOrderClaimEntity>> {
        val ids = orderIds.distinct()
        if (ids.isEmpty()) return Mono.just(emptyList())
        return databaseClient.sqlIn(
            """
            SELECT order_id, run_id, customer, priority, placed_date, reservation_status,
                   reservation_created_at, reservation_expires_at, confirmed_at, expired_at
            FROM demand_order
            WHERE order_id IN (/*in*/)
            """.trimIndent(),
            ids
        )
            .map { row, _ ->
                AllocationOrderClaimEntity(
                    orderId = row.text("order_id"),
                    runId = row.uuid("run_id"),
                    customer = row.text("customer"),
                    priority = row.int("priority"),
                    placedDate = row.date("placed_date"),
                    orderReservationStatus = OrderReservationStatus.valueOf(row.text("reservation_status")),
                    reservationCreatedAt = row.instant("reservation_created_at"),
                    reservationExpiresAt = row.instantOrNull("reservation_expires_at"),
                    confirmedAt = row.instantOrNull("confirmed_at"),
                    expiredAt = row.instantOrNull("expired_at")
                )
            }
            .all()
            .collectList()
    }

    override fun findLines(lineIds: Collection<String>): Mono<List<DemandOrderLineEntity>> {
        val ids = lineIds.distinct()
        if (ids.isEmpty()) return Mono.just(emptyList())
        return databaseClient.sqlIn(
            """
            SELECT order_line_id, order_id, sku, requested_quantity, required_delivery_date, sequence_number
            FROM demand_order_line
            WHERE order_line_id IN (/*in*/)
            """.trimIndent(),
            ids
        )
            .map { row, _ ->
                DemandOrderLineEntity(
                    orderLineId = row.text("order_line_id"),
                    orderId = row.text("order_id"),
                    sku = row.text("sku"),
                    requestedQuantity = row.int("requested_quantity"),
                    requiredDeliveryDate = row.date("required_delivery_date"),
                    sequenceNumber = row.int("sequence_number")
                )
            }
            .all()
            .collectList()
    }

    override fun lockUnreceivedSupply(sku: String): Mono<List<InboundSupplyEntity>> =
        databaseClient.sql(
            """
            SELECT shipment_line_id, shipment_id, lot_id, sku, warehouse_code, expected_quantity,
                   quantity_reserved, expected_arrival_date, received, created_at
            FROM inbound_supply
            WHERE sku = :sku AND received = false
            ORDER BY expected_arrival_date, shipment_id, shipment_line_id
            FOR UPDATE
            """.trimIndent()
        )
            .bind("sku", sku)
            .map { row, _ ->
                InboundSupplyEntity(
                    shipmentLineId = row.text("shipment_line_id"),
                    shipmentId = row.text("shipment_id"),
                    lotId = row.text("lot_id"),
                    sku = row.text("sku"),
                    warehouseCode = row.text("warehouse_code"),
                    expectedQuantity = row.int("expected_quantity"),
                    quantityReserved = row.int("quantity_reserved"),
                    expectedArrivalDate = row.date("expected_arrival_date"),
                    received = row.bool("received"),
                    createdAt = row.instant("created_at")
                )
            }
            .all()
            .collectList()

    override fun lockStock(sku: String): Mono<List<StockEntity>> =
        databaseClient.sql(
            """
            SELECT id, sku, warehouse_code, lot_id, received_date, quantity_on_hand,
                   quantity_reserved, created_at, updated_at
            FROM stock
            WHERE sku = :sku
            ORDER BY received_date, lot_id
            FOR UPDATE
            """.trimIndent()
        )
            .bind("sku", sku)
            .map { row, _ ->
                StockEntity(
                    id = row.uuid("id"),
                    sku = row.text("sku"),
                    warehouseCode = row.text("warehouse_code"),
                    lotId = row.text("lot_id"),
                    receivedDate = row.date("received_date"),
                    quantityOnHand = row.int("quantity_on_hand"),
                    quantityReserved = row.int("quantity_reserved"),
                    createdAt = row.instant("created_at"),
                    updatedAt = row.instant("updated_at")
                )
            }
            .all()
            .collectList()

    override fun lockStockReservations(lineIds: Collection<String>): Mono<List<StockReservationEntity>> {
        val ids = lineIds.distinct()
        if (ids.isEmpty()) return Mono.just(emptyList())
        return databaseClient.sqlIn(
            """
            SELECT id, order_id, order_line_id, stock_id, quantity, required_delivery_date, created_at
            FROM stock_reservation
            WHERE order_line_id IN (/*in*/)
            FOR UPDATE
            """.trimIndent(),
            ids
        )
            .map { row, _ ->
                StockReservationEntity(
                    id = row.uuid("id"),
                    orderId = row.text("order_id"),
                    orderLineId = row.text("order_line_id"),
                    stockId = row.uuid("stock_id"),
                    quantity = row.int("quantity"),
                    requiredDeliveryDate = row.date("required_delivery_date"),
                    createdAt = row.instant("created_at")
                )
            }
            .all()
            .collectList()
    }

    override fun lockInboundReservations(lineIds: Collection<String>): Mono<List<InboundReservationEntity>> {
        val ids = lineIds.distinct()
        if (ids.isEmpty()) return Mono.just(emptyList())
        return databaseClient.sqlIn(
            """
            SELECT id, order_id, order_line_id, shipment_id, shipment_line_id, quantity,
                   required_delivery_date, created_at
            FROM inbound_reservation
            WHERE order_line_id IN (/*in*/)
            FOR UPDATE
            """.trimIndent(),
            ids
        )
            .map { row, _ ->
                InboundReservationEntity(
                    id = row.uuid("id"),
                    orderId = row.text("order_id"),
                    orderLineId = row.text("order_line_id"),
                    shipmentId = row.text("shipment_id"),
                    shipmentLineId = row.text("shipment_line_id"),
                    quantity = row.int("quantity"),
                    requiredDeliveryDate = row.date("required_delivery_date"),
                    createdAt = row.instant("created_at")
                )
            }
            .all()
            .collectList()
    }

    override fun lockBackorders(sku: String): Mono<List<OpenBackorderEntity>> =
        databaseClient.sql(
            """
            SELECT b.order_line_id, b.order_id, b.customer, b.sku, b.priority, b.placed_date,
                   b.required_delivery_date, b.original_quantity, b.remaining_quantity,
                   b.created_at, b.updated_at
            FROM open_backorder b
            JOIN demand_order_line l ON l.order_line_id = b.order_line_id
            WHERE b.sku = :sku
            ORDER BY b.priority, b.placed_date, b.created_at, b.order_id, l.sequence_number
            FOR UPDATE OF b
            """.trimIndent()
        )
            .bind("sku", sku)
            .map { row, _ ->
                OpenBackorderEntity(
                    orderLineId = row.text("order_line_id"),
                    orderId = row.text("order_id"),
                    customer = row.text("customer"),
                    sku = row.text("sku"),
                    priority = row.int("priority"),
                    placedDate = row.date("placed_date"),
                    requiredDeliveryDate = row.date("required_delivery_date"),
                    originalQuantity = row.int("original_quantity"),
                    remainingQuantity = row.int("remaining_quantity"),
                    createdAt = row.instant("created_at"),
                    updatedAt = row.instant("updated_at")
                )
            }
            .all()
            .collectList()

    override fun saveCalculation(calculation: AllocationCalculationResult, now: Instant): Mono<Void> {
        val stockUpdates = Flux.fromIterable(calculation.inventoryLots).concatMap { lot ->
            databaseClient.sql("UPDATE stock SET quantity_reserved = :quantityReserved WHERE id = :id")
                .bind("quantityReserved", lot.quantityReserved)
                .bind("id", lot.stockId)
                .fetch().rowsUpdated().then()
        }
        val supplyUpdates = Flux.fromIterable(calculation.inboundSupplies).concatMap { supply ->
            databaseClient.sql(
                "UPDATE inbound_supply SET quantity_reserved = :quantityReserved WHERE shipment_line_id = :shipmentLineId"
            )
                .bind("quantityReserved", supply.quantityReserved)
                .bind("shipmentLineId", supply.shipmentLineId)
                .fetch().rowsUpdated().then()
        }
        val stockReservations = Flux.fromIterable(calculation.stockReservations).concatMap { reservation ->
            databaseClient.sql(
                """
                INSERT INTO stock_reservation (
                    id, order_id, order_line_id, stock_id, quantity, required_delivery_date, created_at
                ) VALUES (
                    :id, :orderId, :orderLineId, :stockId, :quantity, :requiredDeliveryDate, :createdAt
                )
                ON CONFLICT (id) DO UPDATE SET
                    quantity = EXCLUDED.quantity,
                    required_delivery_date = EXCLUDED.required_delivery_date
                """.trimIndent()
            )
                .bind("id", reservation.id ?: UUID.randomUUID())
                .bind("orderId", reservation.orderId)
                .bind("orderLineId", reservation.orderLineId)
                .bind("stockId", reservation.stockId)
                .bind("quantity", reservation.quantity)
                .bind("requiredDeliveryDate", reservation.requiredDeliveryDate)
                .bind("createdAt", (reservation.createdAt ?: now).atOffset(ZoneOffset.UTC))
                .fetch().rowsUpdated().then()
        }
        val inboundReservations = Flux.fromIterable(calculation.inboundReservations).concatMap { reservation ->
            databaseClient.sql(
                """
                INSERT INTO inbound_reservation (
                    id, order_id, order_line_id, shipment_id, shipment_line_id, quantity,
                    required_delivery_date, created_at
                ) VALUES (
                    :id, :orderId, :orderLineId, :shipmentId, :shipmentLineId, :quantity,
                    :requiredDeliveryDate, :createdAt
                )
                ON CONFLICT (id) DO UPDATE SET
                    quantity = EXCLUDED.quantity,
                    required_delivery_date = EXCLUDED.required_delivery_date
                """.trimIndent()
            )
                .bind("id", reservation.id ?: UUID.randomUUID())
                .bind("orderId", reservation.orderId)
                .bind("orderLineId", reservation.orderLineId)
                .bind("shipmentId", reservation.shipmentId)
                .bind("shipmentLineId", reservation.shipmentLineId)
                .bind("quantity", reservation.quantity)
                .bind("requiredDeliveryDate", reservation.requiredDeliveryDate)
                .bind("createdAt", (reservation.createdAt ?: now).atOffset(ZoneOffset.UTC))
                .fetch().rowsUpdated().then()
        }
        val backorders = Flux.fromIterable(calculation.backordersToRegister).concatMap { backorder ->
            databaseClient.sql(
                """
                INSERT INTO open_backorder (
                    order_line_id, order_id, customer, sku, priority, placed_date, required_delivery_date,
                    original_quantity, remaining_quantity, created_at, updated_at
                ) VALUES (
                    :orderLineId, :orderId, :customer, :sku, :priority, :placedDate, :requiredDeliveryDate,
                    :originalQuantity, :remainingQuantity, :createdAt, :updatedAt
                )
                """.trimIndent()
            )
                .bind("orderLineId", backorder.orderLineId)
                .bind("orderId", backorder.orderId)
                .bind("customer", backorder.customer)
                .bind("sku", backorder.sku)
                .bind("priority", backorder.priority)
                .bind("placedDate", backorder.placedDate)
                .bind("requiredDeliveryDate", backorder.requiredDeliveryDate)
                .bind("originalQuantity", backorder.originalQuantity)
                .bind("remainingQuantity", backorder.quantity)
                .bind("createdAt", now.atOffset(ZoneOffset.UTC))
                .bind("updatedAt", now.atOffset(ZoneOffset.UTC))
                .fetch().rowsUpdated().then()
        }
        return stockUpdates.then()
            .then(supplyUpdates.then())
            .then(stockReservations.then())
            .then(inboundReservations.then())
            .then(backorders.then())
    }

    override fun deleteBackorder(orderLineId: String): Mono<Void> =
        databaseClient.sql("DELETE FROM open_backorder WHERE order_line_id = :orderLineId")
            .bind("orderLineId", orderLineId)
            .fetch().rowsUpdated().then()

    override fun updateBackorderQuantity(orderLineId: String, remainingQuantity: Int, updatedAt: Instant): Mono<Void> =
        databaseClient.sql(
            """
            UPDATE open_backorder
            SET remaining_quantity = :remainingQuantity, updated_at = :updatedAt
            WHERE order_line_id = :orderLineId
            """.trimIndent()
        )
            .bind("remainingQuantity", remainingQuantity)
            .bind("updatedAt", updatedAt.atOffset(ZoneOffset.UTC))
            .bind("orderLineId", orderLineId)
            .fetch().rowsUpdated().then()

    override fun saveOperation(runId: UUID, sku: String, requestHash: String, resultJson: String): Mono<Void> =
        databaseClient.sql(
            """
            INSERT INTO allocation_sku_operation (id, run_id, sku, request_hash, result_json)
            VALUES (:id, :runId, :sku, :requestHash, :resultJson)
            """.trimIndent()
        )
            .bind("id", UUID.randomUUID())
            .bind("runId", runId)
            .bind("sku", sku)
            .bind("requestHash", requestHash)
            .bind("resultJson", resultJson)
            .then()

    private fun loadRecords(runId: UUID, sku: String): Mono<List<AllocationRecord>> =
        databaseClient.sql(
            """
            SELECT id, order_id, order_line_id, customer, sku, priority, placed_date, required_delivery_date,
                   requested_quantity, allocated_from_inventory, allocated_from_inbound, backordered_quantity,
                   status, processing_outcome
            FROM allocation_record
            WHERE run_id = :runId AND sku = :sku
            ORDER BY sequence_number
            """.trimIndent()
        )
            .bind("runId", runId)
            .bind("sku", sku)
            .map { row, _ ->
                StoredRecord(
                    id = row.uuid("id"),
                    orderId = row.text("order_id"),
                    orderLineId = row.text("order_line_id"),
                    customer = row.text("customer"),
                    sku = row.text("sku"),
                    priority = row.int("priority"),
                    placedDate = row.date("placed_date"),
                    requiredDeliveryDate = row.date("required_delivery_date"),
                    requestedQuantity = row.int("requested_quantity"),
                    allocatedFromInventory = row.int("allocated_from_inventory"),
                    allocatedFromInbound = row.int("allocated_from_inbound"),
                    backorderedQuantity = row.int("backordered_quantity"),
                    status = row.textOrNull("status")?.let { AllocationStatus.valueOf(it) },
                    processingOutcome = AllocationProcessingOutcome.valueOf(row.text("processing_outcome"))
                )
            }
            .all()
            .collectList()
            .flatMap { records ->
                if (records.isEmpty()) {
                    Mono.just(emptyList())
                } else {
                    loadSources(records.map { it.id }).map { sources ->
                        records.map { record -> record.toAllocationRecord(sources[record.id].orEmpty()) }
                    }
                }
            }

    private fun loadSources(recordIds: List<UUID>): Mono<Map<UUID, List<AllocationSource>>> =
        databaseClient.sqlIn(
            """
            SELECT allocation_record_id, source_type, source_id, shipment_id, shipment_line_id,
                   quantity, source_date, warehouse_location
            FROM allocation_source
            WHERE allocation_record_id IN (/*in*/)
            """.trimIndent(),
            recordIds
        )
            .map { row, _ ->
                row.uuid("allocation_record_id") to AllocationSource(
                    sourceType = AllocationSourceType.valueOf(row.text("source_type")),
                    sourceId = row.text("source_id"),
                    quantity = row.int("quantity"),
                    sourceDate = row.date("source_date"),
                    warehouseLocation = row.textOrNull("warehouse_location"),
                    shipmentId = row.textOrNull("shipment_id"),
                    shipmentLineId = row.textOrNull("shipment_line_id")
                )
            }
            .all()
            .collectList()
            .map { rows -> rows.groupBy({ it.first }, { it.second }) }

    private fun DatabaseClient.sqlIn(statement: String, values: List<Any>): DatabaseClient.GenericExecuteSpec {
        val placeholders = values.indices.joinToString(", ") { ":v$it" }
        var spec = sql(statement.replace("/*in*/", placeholders))
        values.forEachIndexed { index, value -> spec = spec.bind("v$index", value) }
        return spec
    }
}

private data class StoredRecord(
    val id: UUID,
    val orderId: String,
    val orderLineId: String,
    val customer: String,
    val sku: String,
    val priority: Int,
    val placedDate: LocalDate,
    val requiredDeliveryDate: LocalDate,
    val requestedQuantity: Int,
    val allocatedFromInventory: Int,
    val allocatedFromInbound: Int,
    val backorderedQuantity: Int,
    val status: AllocationStatus?,
    val processingOutcome: AllocationProcessingOutcome
) {
    fun toAllocationRecord(sources: List<AllocationSource>) = AllocationRecord(
        orderId = orderId,
        orderLineId = orderLineId,
        customer = customer,
        sku = sku,
        priority = priority,
        placedDate = placedDate,
        requiredDeliveryDate = requiredDeliveryDate,
        requestedQuantity = requestedQuantity,
        allocatedFromInventory = allocatedFromInventory,
        allocatedFromInbound = allocatedFromInbound,
        backorderedQuantity = backorderedQuantity,
        status = status,
        sources = sources,
        processingOutcome = processingOutcome
    )
}

private fun Row.text(name: String): String = get(name, String::class.java)
    ?: error("Column '$name' is null.")

private fun Row.textOrNull(name: String): String? = get(name, String::class.java)

private fun Row.int(name: String): Int = when (val value = get(name)) {
    is Number -> value.toInt()
    else -> error("Column '$name' is ${value?.javaClass?.name}.")
}

private fun Row.bool(name: String): Boolean = when (val value = get(name)) {
    is Boolean -> value
    else -> error("Column '$name' is ${value?.javaClass?.name}.")
}

private fun Row.uuid(name: String): UUID = when (val value = get(name)) {
    is UUID -> value
    is String -> UUID.fromString(value)
    else -> error("Column '$name' is ${value?.javaClass?.name}.")
}

private fun Row.date(name: String): LocalDate = when (val value = get(name)) {
    is LocalDate -> value
    else -> error("Column '$name' is ${value?.javaClass?.name}.")
}

private fun Row.instantOrNull(name: String): Instant? = when (val value = get(name)) {
    null -> null
    is Instant -> value
    is OffsetDateTime -> value.toInstant()
    else -> error("Column '$name' is ${value.javaClass.name}.")
}

private fun Row.instant(name: String): Instant = instantOrNull(name) ?: error("Column '$name' is null.")
