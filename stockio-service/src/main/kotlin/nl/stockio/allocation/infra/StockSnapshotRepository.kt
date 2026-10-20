package nl.stockio.allocation.infra

import java.sql.Timestamp
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

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
class StockSnapshotRepository(
    private val jdbcTemplate: JdbcTemplate
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun replaceFrom(
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

    fun findDailyByDate(snapshotDate: LocalDate): List<StockDailySnapshot> =
        jdbcTemplate.query(
            "SELECT * FROM stock_daily_snapshots WHERE snapshot_date = ? ORDER BY sku, warehouse_code",
            dailyRowMapper,
            snapshotDate
        )

    fun findLotsByDate(snapshotDate: LocalDate): List<StockDailyLotSnapshot> =
        jdbcTemplate.query(
            "SELECT * FROM stock_daily_lot_snapshots WHERE snapshot_date = ? ORDER BY sku, warehouse_code, lot_id",
            lotRowMapper,
            snapshotDate
        )

    fun findDailyBySkuAndWarehouse(sku: String, warehouseCode: String): List<StockDailySnapshot> =
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
