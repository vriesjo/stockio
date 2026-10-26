package nl.stockio.stock.infra

import jakarta.persistence.LockModeType
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import nl.stockio.allocation.domain.model.StockSyncResult
import nl.stockio.stock.domain.port.AllocationJobResultRepository
import nl.stockio.stock.domain.port.StockLedgerSyncStatusRepository
import nl.stockio.stock.domain.port.StockProjectionLockRepository
import nl.stockio.stock.domain.port.StockRepository
import nl.stockio.stock.domain.port.StockReservationRepository
import nl.stockio.stock.domain.port.StockSnapshotRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Transactional(propagation = Propagation.MANDATORY)
interface StockProjectionLockJpaRepository : StockProjectionLockRepository, JpaRepository<StockProjectionLockEntity, Int> {
    override fun acquireAllocationReadLock() {
        checkNotNull(lockForAllocation()) { "The stock projection lock row is missing." }
    }

    override fun acquire() {
        checkNotNull(lockForProjection()) { "The stock projection lock row is missing." }
    }

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select l from StockProjectionLockEntity l where l.id = 1")
    fun lockForAllocation(): StockProjectionLockEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StockProjectionLockEntity l where l.id = 1")
    fun lockForProjection(): StockProjectionLockEntity?
}

interface StockJpaRepository : StockRepository, JpaRepository<StockEntity, UUID> {
    override fun findAll(): List<StockEntity>
    override fun <S : StockEntity> saveAll(entities: Iterable<S>): List<S>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockEntity s where s.sku in :skus order by s.receivedDate, s.lotId")
    override fun lockAllBySkuIn(@Param("skus") skus: Collection<String>): List<StockEntity>
}

interface AllocationJobResultJpaRepository : AllocationJobResultRepository, JpaRepository<AllocationJobResultEntity, UUID> {
    override fun findById(jobId: UUID): Optional<AllocationJobResultEntity>
    override fun <S : AllocationJobResultEntity> save(entity: S): S
}

interface StockReservationJpaRepository : StockReservationRepository, JpaRepository<StockReservationEntity, UUID> {
    override fun findAll(): List<StockReservationEntity>
    override fun deleteAll(entities: Iterable<StockReservationEntity>)
    override fun <S : StockReservationEntity> saveAll(entities: Iterable<S>): List<S>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservationEntity r where r.orderLineId in :lineIds")
    override fun lockAllByOrderLineIdIn(@Param("lineIds") lineIds: Collection<String>): List<StockReservationEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservationEntity r where r.orderId in :orderIds")
    override fun lockAllByOrderIdIn(@Param("orderIds") orderIds: Collection<String>): List<StockReservationEntity>

    override fun findAllByOrderIdIn(orderIds: Collection<String>): List<StockReservationEntity>

    override fun findAllByRequiredDeliveryDateLessThanEqual(requiredDeliveryDate: LocalDate): List<StockReservationEntity>
}

data class StockLedgerSyncState(
    val monitoringStartedAt: Instant,
    val lastSuccessAt: Instant?,
    val snapshotDate: LocalDate?,
    val skuCount: Int?,
    val lotSnapshotCount: Int?
)

@Repository
class StockLedgerSyncStatusJdbcTemplateRepository(private val jdbcTemplate: JdbcTemplate):
    StockLedgerSyncStatusRepository {
    override fun getSyncStatus(): StockLedgerSyncState = checkNotNull(jdbcTemplate.queryForObject(
        "SELECT * FROM stock_ledger_sync_status WHERE id = 1",
        { resultSet, _ ->
            StockLedgerSyncState(
                monitoringStartedAt = resultSet.getTimestamp("monitoring_started_at").toInstant(),
                lastSuccessAt = resultSet.getTimestamp("last_success_at")?.toInstant(),
                snapshotDate = resultSet.getObject("snapshot_date", LocalDate::class.java),
                skuCount = resultSet.getObject("sku_count", Int::class.javaObjectType),
                lotSnapshotCount = resultSet.getObject("lot_snapshot_count", Int::class.javaObjectType)
            )
        }
    )) { "The ledger synchronization status row is missing." }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun recordSuccess(result: StockSyncResult) {
        check(jdbcTemplate.update(
            """
                UPDATE stock_ledger_sync_status
                SET last_success_at = ?, snapshot_date = ?, sku_count = ?, lot_snapshot_count = ?
                WHERE id = 1
            """.trimIndent(),
            Timestamp.from(result.updatedAt), result.snapshotDate, result.skuCount, result.lotCount
        ) == 1) { "The ledger synchronization status row is missing." }
    }
}

/**
 * Reads and replaces derived stock forecasts using JDBC rather than JPA entity tracking.
 *
 * Snapshots follow a discard-and-rebuild lifecycle, unlike orders, reservations, and live stock.
 * A JPA bulk delete would remove snapshot rows without evicting their managed entities, leaving
 * stale snapshots in the persistence context during repeated rebuilds in one transaction.
 * Native bulk queries on a JpaRepository would have the same limitation. Entity-by-entity JPA
 * deletion is possible, but requires loading snapshots and carefully ordering deletes and inserts
 * when reusing their keys.
 *
 * JDBC avoids that mismatch: reads return plain snapshot values, and replacement deletes rows
 * and batch-inserts their successors without managing snapshot entities. No persistence-context
 * clearing is needed, so unrelated JPA-managed business entities remain attached.
 *
 * JDBC uses the same datasource and transaction as the JPA business operations. [replaceFrom]
 * requires an existing transaction and the caller must hold the exclusive projection lock.
 * Snapshot replacement and participating business changes commit or roll back together.
 */
@Repository
@ConditionalOnProperty(prefix = "stockio.stock.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
class StockSnapshotRepositoryJdbcTemplate(
    private val jdbcTemplate: JdbcTemplate
) : StockSnapshotRepository {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun replaceFrom(
        startDate: LocalDate,
        dailySnapshots: List<StockDailySnapshot>,
        lotSnapshots: List<StockDailyLotSnapshot>
    ) {
        require(dailySnapshots.all { it.snapshotDate >= startDate } &&
            lotSnapshots.all { it.snapshotDate >= startDate }) {
            "Replacement snapshots must not precede the replacement start date."
        }
        jdbcTemplate.update("DELETE FROM stock_daily_lot_snapshots WHERE snapshot_date >= ?", startDate)
        jdbcTemplate.update("DELETE FROM stock_daily_snapshots WHERE snapshot_date >= ?", startDate)
        jdbcTemplate.batchUpdate(
            """
                INSERT INTO stock_daily_snapshots (
                    id, snapshot_date, sku, warehouse_code, opening_stock, inbound_qty,
                    demand_qty, allocated_qty, closing_stock, shortage_qty, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            dailySnapshots,
            500
        ) { statement, snapshot ->
            statement.setObject(1, snapshot.id)
            statement.setObject(2, snapshot.snapshotDate)
            statement.setString(3, snapshot.sku)
            statement.setString(4, snapshot.warehouseCode)
            statement.setInt(5, snapshot.openingStock)
            statement.setInt(6, snapshot.inboundQuantity)
            statement.setInt(7, snapshot.demandQuantity)
            statement.setInt(8, snapshot.allocatedQuantity)
            statement.setInt(9, snapshot.closingStock)
            statement.setInt(10, snapshot.shortageQuantity)
            statement.setTimestamp(11, Timestamp.from(snapshot.createdAt))
        }
        jdbcTemplate.batchUpdate(
            """
                INSERT INTO stock_daily_lot_snapshots (
                    snapshot_date, sku, warehouse_code, lot_id, received_date, opening_stock,
                    inbound_qty, allocated_qty, closing_stock, quantity_reserved, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            lotSnapshots,
            500
        ) { statement, snapshot ->
            statement.setObject(1, snapshot.snapshotDate)
            statement.setString(2, snapshot.sku)
            statement.setString(3, snapshot.warehouseCode)
            statement.setString(4, snapshot.lotId)
            statement.setObject(5, snapshot.receivedDate)
            statement.setInt(6, snapshot.openingStock)
            statement.setInt(7, snapshot.inboundQuantity)
            statement.setInt(8, snapshot.allocatedQuantity)
            statement.setInt(9, snapshot.closingStock)
            statement.setInt(10, snapshot.quantityReserved)
            statement.setTimestamp(11, Timestamp.from(snapshot.createdAt))
        }
    }

    override fun findDailyByDate(snapshotDate: LocalDate): List<StockDailySnapshot> =
        jdbcTemplate.query(
            "SELECT * FROM stock_daily_snapshots WHERE snapshot_date = ? ORDER BY sku, warehouse_code",
            dailyRowMapper,
            snapshotDate
        )

    override fun findLotsByDate(snapshotDate: LocalDate): List<StockDailyLotSnapshot> =
        jdbcTemplate.query(
            "SELECT * FROM stock_daily_lot_snapshots WHERE snapshot_date = ? ORDER BY sku, warehouse_code, lot_id",
            lotRowMapper,
            snapshotDate
        )

    override fun findDailyBySkuAndWarehouse(sku: String, warehouseCode: String): List<StockDailySnapshot> =
        jdbcTemplate.query(
            "SELECT * FROM stock_daily_snapshots WHERE sku = ? AND warehouse_code = ? ORDER BY snapshot_date",
            dailyRowMapper,
            sku,
            warehouseCode
        )

    private val dailyRowMapper = RowMapper { resultSet, _ ->
        StockDailySnapshot(
            id = resultSet.getObject("id", UUID::class.java),
            snapshotDate = resultSet.getObject("snapshot_date", LocalDate::class.java),
            sku = resultSet.getString("sku"),
            warehouseCode = resultSet.getString("warehouse_code"),
            openingStock = resultSet.getInt("opening_stock"),
            inboundQuantity = resultSet.getInt("inbound_qty"),
            demandQuantity = resultSet.getInt("demand_qty"),
            allocatedQuantity = resultSet.getInt("allocated_qty"),
            closingStock = resultSet.getInt("closing_stock"),
            shortageQuantity = resultSet.getInt("shortage_qty"),
            createdAt = resultSet.getTimestamp("created_at").toInstant()
        )
    }

    private val lotRowMapper = RowMapper { resultSet, _ ->
        StockDailyLotSnapshot(
            snapshotDate = resultSet.getObject("snapshot_date", LocalDate::class.java),
            sku = resultSet.getString("sku"),
            warehouseCode = resultSet.getString("warehouse_code"),
            lotId = resultSet.getString("lot_id"),
            receivedDate = resultSet.getObject("received_date", LocalDate::class.java),
            openingStock = resultSet.getInt("opening_stock"),
            inboundQuantity = resultSet.getInt("inbound_qty"),
            allocatedQuantity = resultSet.getInt("allocated_qty"),
            closingStock = resultSet.getInt("closing_stock"),
            quantityReserved = resultSet.getInt("quantity_reserved"),
            createdAt = resultSet.getTimestamp("created_at").toInstant()
        )
    }
}
