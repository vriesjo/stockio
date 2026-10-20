package nl.stockio.allocation.domain.processor

import java.time.Clock
import java.time.LocalDate
import nl.stockio.allocation.domain.api.data.ReservationStatus
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.AllocationOrderClaimRepository
import nl.stockio.allocation.infra.InboundReservationRepository
import nl.stockio.allocation.infra.InboundSupplyRepository
import nl.stockio.allocation.infra.OpenBackorderRepository
import nl.stockio.allocation.infra.StockDailyLotSnapshot
import nl.stockio.allocation.infra.StockDailySnapshot
import nl.stockio.allocation.infra.StockSnapshotRepository
import nl.stockio.allocation.infra.StockProjectionLockRepository
import nl.stockio.allocation.infra.StockRepository
import nl.stockio.allocation.infra.StockReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Rebuilds daily stock forecasts from persisted inventory, inbound supply, and order commitments.
 *
 * The projection includes records from all allocation runs, grouped by SKU, warehouse, and
 * receiving lot. Each unreceived shipment line contributes its full expected quantity on arrival,
 * whether reserved or not. Only confirmed orders generate outgoing deliveries and backorder
 * shortages; unconfirmed orders remain reserved holds. Shortages are assigned to warehouse
 * `MAIN` and do not reduce physical stock.
 *
 * For each projected day, closing stock equals opening stock plus arrivals minus confirmed
 * allocated deliveries. Demand equals those deliveries plus confirmed backorder shortages.
 * Lot snapshots also track the reserved quantity remaining after each day's movements.
 *
 * For example, with 10 units on hand, 5 arriving tomorrow, and 8 reserved for a confirmed order
 * due the day after tomorrow, projected closing balances are 10, 15, and 7. If the order is
 * unconfirmed, its reservation does not cause the final reduction.
 *
 * This processor only replaces forecast snapshots; it does not allocate demand, receive supply,
 * deliver orders, or expire reservations. [StockLedgerProcessor] applies today's forecast to live
 * stock. Expiry cleanup is a separate operation, and future TTL expiry is not simulated here.
 */
@Service
class StockProjectionProcessor(
    private val stockProjectionLockRepository: StockProjectionLockRepository,
    private val stockRepository: StockRepository,
    private val stockReservationRepository: StockReservationRepository,
    private val inboundSupplyRepository: InboundSupplyRepository,
    private val inboundReservationRepository: InboundReservationRepository,
    private val backorderRepository: OpenBackorderRepository,
    private val snapshotRepository: StockSnapshotRepository,
    private val claimRepository: AllocationOrderClaimRepository,
    private val clock: Clock
) {

    /**
     * Replaces aggregate and lot-level snapshots from `max(asOfDate, today)` onward.
     *
     * Acquires the exclusive stock-projection lock for the transaction before loading the
     * baseline. Overdue arrivals and confirmed deliveries or shortages are moved to the start
     * date; historical stock is not reconstructed. The inclusive horizon ends at the latest
     * such event, or the start date if none exist. Pending order delivery dates do not extend it.
     *
     * Earlier snapshots are retained. Snapshots from the start date onward are deleted even if
     * there are no remaining stock or confirmed shortage scopes to project. Snapshot replacement
     * uses JDBC in the same transaction without detaching any managed business entities.
     *
     * Missing order claims or supply references, active demand on expired orders, conflicting
     * lots, and deliveries exceeding available on-hand or reserved quantities fail the transaction.
     * Snapshot deletion and replacement roll back together on failure.
     *
     * @param asOfDate requested projection start, clamped to today's date using the injected [Clock].
     */
    @Transactional
    fun process(asOfDate: LocalDate) {
        stockProjectionLockRepository.acquire()

        val startDate = maxOf(asOfDate, LocalDate.now(clock))
        val claims = claimRepository.findAll().associateBy { it.orderId }

        val stocks = stockRepository.findAll()
        val supplies = inboundSupplyRepository.findAll().filterNot { it.received }
        val stockById = stocks.associateBy { it.id }
        val supplyById = supplies.associateBy { it.shipmentLineId }
        val supplyByLot = supplies.associateBy { LotKey(it.sku, it.warehouseCode, it.lotId) }

        // Build the initial lot states from existing stock and unreceived inbound supply.
        // Use a LinkedHashMap to preserve insertion order for deterministic snapshot generation.
        val states = linkedMapOf<LotKey, LotState>()
        stocks.forEach { stock ->
            val key = LotKey(stock.sku, stock.warehouseCode, stock.lotId)
            states[key] = LotState(
                receivedDate = stock.receivedDate,
                onHand = stock.quantityOnHand,
                reserved = stock.quantityReserved
            )
        }

        // Track the earliest date each lot is expected to arrive, for use in the daily rebuild loop.
        // This is needed because the inbound supply may be filtered to only unreceived shipments, but
        // the projection must still include the expected arrival date for each lot to determine when
        // to add its expected quantity to the on-hand balance.
        val inboundDateByLot = mutableMapOf<LotKey, LocalDate>()
        supplies.forEach { supply ->
            val key = LotKey(supply.sku, supply.warehouseCode, supply.lotId)
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
        // Only confirmed reservations contribute to projected allocations; unconfirmed reservations
        // remain reserved but do not reduce on-hand stock. Reservations for missing lots fail the rebuild
        // transaction, as do reservations for missing order claims or expired orders.
        stockReservations.forEach { reservation ->
            val stock = stockById[reservation.stockId]
                ?: error("Stock reservation '${reservation.id}' references missing stock.")
            val key = LotKey(stock.sku, stock.warehouseCode, stock.lotId)
            require(key in states) { "Reserved stock lot '${stock.lotId}' is missing from the projection." }
            if (isConfirmed(reservation.orderId, claims)) {
                addQuantity(
                    allocatedByLotDate,
                    LotDate(key, maxOf(reservation.requiredDeliveryDate, startDate)),
                    reservation.quantity
                )
            }
        }

        // Only confirmed inbound reservations contribute to projected allocations; unconfirmed reservations
        // remain reserved but do not reduce on-hand stock. Reservations for missing lots fail the rebuild
        // transaction, as do reservations for missing order claims or expired orders.
        val inboundReservations = inboundReservationRepository.findAll()
        inboundReservations.forEach { reservation ->
            val supply = supplyById[reservation.shipmentLineId]
                ?: throw IllegalStateException("Inbound reservation '${reservation.id}' references missing supply.")
            val key = LotKey(supply.sku, supply.warehouseCode, supply.lotId)
            require(key in states) { "Reserved inbound lot '${supply.shipmentId}' is missing from the projection." }
            if (isConfirmed(reservation.orderId, claims)) {
                addQuantity(
                    allocatedByLotDate,
                    LotDate(key, maxOf(reservation.requiredDeliveryDate, startDate)),
                    reservation.quantity
                )
            }
        }

        val backordersByDate = mutableMapOf<ScopeDate, Int>()
        val backorders = backorderRepository.findAll()
        backorders.forEach { backorder ->
            if (isConfirmed(backorder.orderId, claims)) {
                addQuantity(
                    backordersByDate,
                    ScopeDate(backorder.sku, "MAIN", maxOf(backorder.requiredDeliveryDate, startDate)),
                    backorder.remainingQuantity
                )
            }
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

        val dailySnapshots = mutableListOf<StockDailySnapshot>()
        val lotSnapshots = mutableListOf<StockDailyLotSnapshot>()
        scopes.forEach { (sku, warehouseCode) ->
            val skuLots = states.filterKeys { it.sku == sku && it.warehouseCode == warehouseCode }
            var date = startDate
            while (!date.isAfter(horizon)) {
                val opening = skuLots.values.sumOf { it.onHand.toLong() }.toIntExact()
                var inboundQuantity = 0L
                var allocatedQuantity = 0L

                skuLots.forEach { (key, state) ->
                    if (inboundDateByLot[key] == date) {
                        val supply = supplyByLot[key]
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
                dailySnapshots.add(
                    StockDailySnapshot(
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

                lotSnapshots.addAll(
                    skuLots.map { (key, state) ->
                        StockDailyLotSnapshot(
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
        snapshotRepository.replaceFrom(startDate, dailySnapshots, lotSnapshots)
    }

    fun isConfirmed(orderId: String, claims: Map<String, AllocationOrderClaimEntity>): Boolean {
        val claim = checkNotNull(claims[orderId]) {
            "Active reservation or backorder '$orderId' has no allocation order claim."
        }
        check(claim.reservationStatus != ReservationStatus.EXPIRED) {
            "Expired order '$orderId' still has active reservations or backorders."
        }
        return claim.reservationStatus == ReservationStatus.CONFIRMED
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
