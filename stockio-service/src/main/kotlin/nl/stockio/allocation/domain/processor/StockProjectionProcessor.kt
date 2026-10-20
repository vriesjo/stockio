package nl.stockio.allocation.domain.processor

import java.time.LocalDate
import nl.stockio.allocation.infra.InboundReservationRepository
import nl.stockio.allocation.infra.InboundSupplyRepository
import nl.stockio.allocation.infra.OpenBackorderRepository
import nl.stockio.allocation.infra.StockDailyLotSnapshotEntity
import nl.stockio.allocation.infra.StockDailyLotSnapshotRepository
import nl.stockio.allocation.infra.StockDailySnapshotEntity
import nl.stockio.allocation.infra.StockDailySnapshotRepository
import nl.stockio.allocation.infra.StockRepository
import nl.stockio.allocation.infra.StockReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class StockProjectionProcessor(
    private val stockProjectionLockService: StockProjectionLockService,
    private val stockRepository: StockRepository,
    private val stockReservationRepository: StockReservationRepository,
    private val inboundSupplyRepository: InboundSupplyRepository,
    private val inboundReservationRepository: InboundReservationRepository,
    private val backorderRepository: OpenBackorderRepository,
    private val stockDailyLotSnapshotRepository: StockDailyLotSnapshotRepository,
    private val stockDailySnapshotRepository: StockDailySnapshotRepository
) {

    /**
     * It works in these stages:
     *
     * 1. Lock and load the baseline. It acquires the projection lock to prevent concurrent allocation changes, then loads all live stock,
     *    unreceived inbound shipments, reservations, and open backorders—including records from previous runs.
     * 2. Choose the projection period. It starts at  max(asOfDate, today)  and ends at the latest relevant shipment arrival,
     *    reservation delivery date, or backorder delivery date. Overdue events are moved to the start date; it does not reconstruct history.
     * 3. Turn persisted records into dated movements.
     *    • Each unreceived shipment adds its full expected quantity on its arrival date.
     *    • Each reservation removes its quantity on the order’s required delivery date—not when the reservation was created.
     *    • Each open backorder contributes a shortage on its required delivery date, without reducing physical stock. Currently, shortages are assigned to warehouse  "MAIN" .
     * 4. Simulate each day, per SKU and warehouse. It adds arrivals, subtracts reserved deliveries, and carries the closing stock into the next day:
     *    • closing stock = opening stock + inbound − allocated deliveries
     *    • demand = allocated deliveries + backorder shortage
     *    It also tracks the same movements and remaining reservations for each individual lot.
     * 5. Replace the saved forecast. It deletes snapshots from the start date onward and writes both aggregate and lot-level snapshots.
     *    Earlier snapshots remain untouched. Everything runs in one transaction, so an error rolls the rebuild back.
     *
     * For example:
     *    • With 10 units on hand, 5 arriving tomorrow, and 8 reserved for delivery the day after tomorrow,
     *    • The projected closing balances are 10 → 15 → 7.
     *    • Reserving those 8 units does not immediately remove them from physical stock.
     *
     * The distinction:
     *    •  Allocation decides which supply an order reserves;
     *    •  This processor projects when that supply arrives and leaves.
     *    •  A separate ledger synchronization step applies today’s projected closing balances to live stock.
     *
     */
    @Transactional
    fun process(asOfDate: LocalDate) {
        stockProjectionLockService.acquire()
        val startDate = maxOf(asOfDate, LocalDate.now())
        val stocks = stockRepository.findAll()
        val supplies = inboundSupplyRepository.findAll().filterNot { it.received }
        val stockById = stocks.associateBy { it.id }
        val supplyById = supplies.associateBy { it.shipmentId }
        val states = linkedMapOf<LotKey, LotState>()

        stocks.forEach { stock ->
            val key = LotKey(stock.sku, stock.warehouseCode, stock.lotId)
            states[key] = LotState(
                receivedDate = stock.receivedDate,
                onHand = stock.quantityOnHand,
                reserved = stock.quantityReserved
            )
        }

        val inboundDateByLot = mutableMapOf<LotKey, LocalDate>()
        supplies.forEach { supply ->
            val key = LotKey(supply.sku, supply.warehouseCode, supply.shipmentId)
            require(key !in states) {
                "Inbound shipment '${supply.shipmentId}' conflicts with an existing stock lot."
            }
            states[key] = LotState(
                receivedDate = supply.expectedArrivalDate,
                onHand = 0,
                reserved = 0,
                pendingInboundReservation = supply.quantityReserved
            )
            inboundDateByLot[key] = maxOf(supply.expectedArrivalDate, startDate)
        }

        val allocatedByLotDate = mutableMapOf<LotDate, Int>()
        val stockReservations = stockReservationRepository.findAll()
        stockReservations.forEach { reservation ->
            val stock = stockById[reservation.stockId]
                ?: throw IllegalStateException("Stock reservation '${reservation.id}' references missing stock.")
            val key = LotKey(stock.sku, stock.warehouseCode, stock.lotId)
            require(key in states) { "Reserved stock lot '${stock.lotId}' is missing from the projection." }
            addQuantity(
                allocatedByLotDate,
                LotDate(key, maxOf(reservation.requiredDeliveryDate, startDate)),
                reservation.quantity
            )
        }

        val inboundReservations = inboundReservationRepository.findAll()
        inboundReservations.forEach { reservation ->
            val supply = supplyById[reservation.shipmentId]
                ?: throw IllegalStateException("Inbound reservation '${reservation.id}' references missing supply.")
            val key = LotKey(supply.sku, supply.warehouseCode, supply.shipmentId)
            require(key in states) { "Reserved inbound lot '${supply.shipmentId}' is missing from the projection." }
            addQuantity(
                allocatedByLotDate,
                LotDate(key, maxOf(reservation.requiredDeliveryDate, startDate)),
                reservation.quantity
            )
        }

        val backordersByDate = mutableMapOf<ScopeDate, Int>()
        val backorders = backorderRepository.findAll()
        backorders.forEach { backorder ->
            addQuantity(
                backordersByDate,
                ScopeDate(backorder.sku, "MAIN", maxOf(backorder.requiredDeliveryDate, startDate)),
                backorder.remainingQuantity
            )
        }

        val scopes = (states.keys.map { it.sku to it.warehouseCode } +
            backordersByDate.keys.map { it.sku to it.warehouseCode })
            .toSortedSet(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        val horizon = maxOf(
            startDate,
            inboundDateByLot.values.maxOrNull() ?: startDate,
            allocatedByLotDate.keys.maxOfOrNull { it.date } ?: startDate,
            backordersByDate.keys.maxOfOrNull { it.date } ?: startDate
        )

        stockDailyLotSnapshotRepository.deleteAllBySnapshotDateGreaterThanEqual(startDate)
        stockDailySnapshotRepository.deleteAllBySnapshotDateGreaterThanEqual(startDate)
        if (scopes.isEmpty()) return

        scopes.forEach { (sku, warehouseCode) ->
            val skuLots = states.filterKeys { it.sku == sku && it.warehouseCode == warehouseCode }
            var date = startDate
            while (!date.isAfter(horizon)) {
                val opening = skuLots.values.sumOf { it.onHand.toLong() }.toIntExact()
                var inboundQuantity = 0L
                var allocatedQuantity = 0L

                skuLots.forEach { (key, state) ->
                    if (inboundDateByLot[key] == date) {
                        val supply = supplyById[key.lotId]
                            ?: throw IllegalStateException("Projected inbound lot '${key.lotId}' has no supply record.")
                        state.inboundToday = supply.expectedQuantity
                        state.onHand = Math.addExact(state.onHand, supply.expectedQuantity)
                        state.reserved = Math.addExact(state.reserved, state.pendingInboundReservation)
                        state.pendingInboundReservation = 0
                        inboundQuantity += supply.expectedQuantity.toLong()
                    } else {
                        state.inboundToday = 0
                    }

                    val allocated = allocatedByLotDate[LotDate(key, date)] ?: 0
                    if (allocated > 0) {
                        require(state.reserved >= allocated && state.onHand >= allocated) {
                            "Projected allocation exceeds stock for lot '${key.lotId}' on $date."
                        }
                        state.reserved -= allocated
                        state.onHand -= allocated
                        state.allocatedToday = allocated
                        allocatedQuantity += allocated.toLong()
                    } else {
                        state.allocatedToday = 0
                    }
                }

                val shortage = backordersByDate[ScopeDate(sku, warehouseCode, date)] ?: 0
                val demand = allocatedQuantity + shortage
                val closing = skuLots.values.sumOf { it.onHand.toLong() }.toIntExact()
                stockDailySnapshotRepository.save(
                    StockDailySnapshotEntity(
                        snapshotDate = date,
                        sku = sku,
                        warehouseCode = warehouseCode,
                        openingStock = opening,
                        inboundQuantity = inboundQuantity.toIntExact(),
                        demandQuantity = demand.toIntExact(),
                        allocatedQuantity = allocatedQuantity.toIntExact(),
                        closingStock = closing,
                        shortageQuantity = shortage
                    )
                )

                stockDailyLotSnapshotRepository.saveAll(
                    skuLots.map { (key, state) ->
                        StockDailyLotSnapshotEntity(
                            snapshotDate = date,
                            sku = sku,
                            warehouseCode = warehouseCode,
                            lotId = key.lotId,
                            receivedDate = state.receivedDate,
                            openingStock = state.openingToday,
                            inboundQuantity = state.inboundToday,
                            allocatedQuantity = state.allocatedToday,
                            closingStock = state.onHand,
                            quantityReserved = state.reserved
                        )
                    }
                )
                skuLots.values.forEach { it.openingToday = it.onHand }
                date = date.plusDays(1)
            }
        }
    }

    private fun <K> addQuantity(values: MutableMap<K, Int>, key: K, quantity: Int) {
        values[key] = Math.addExact(values[key] ?: 0, quantity)
    }

    private fun Long.toIntExact(): Int = Math.toIntExact(this)

    private data class LotKey(val sku: String, val warehouseCode: String, val lotId: String)
    private data class LotDate(val lot: LotKey, val date: LocalDate)
    private data class ScopeDate(val sku: String, val warehouseCode: String, val date: LocalDate)

    private class LotState(
        val receivedDate: LocalDate,
        var onHand: Int,
        var reserved: Int,
        var pendingInboundReservation: Int = 0,
        var openingToday: Int = onHand,
        var inboundToday: Int = 0,
        var allocatedToday: Int = 0
    )
}
