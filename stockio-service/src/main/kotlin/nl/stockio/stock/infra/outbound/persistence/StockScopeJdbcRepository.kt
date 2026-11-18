package nl.stockio.stock.infra

import nl.stockio.stock.domain.port.StockScopeRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class StockScopeJdbcRepository(private val jdbcTemplate: JdbcTemplate) : StockScopeRepository {
    override fun findSkus(): List<String> = jdbcTemplate.queryForList(
        """
        SELECT sku FROM stock
        UNION SELECT sku FROM inbound_supply
        UNION SELECT s.sku FROM stock_reservation r JOIN stock s ON s.id = r.stock_id
        UNION SELECT s.sku FROM inbound_reservation r JOIN inbound_supply s ON s.shipment_line_id = r.shipment_line_id
        UNION SELECT sku FROM open_backorder
        UNION SELECT sku FROM stock_daily_snapshots
        UNION SELECT sku FROM stock_daily_lot_snapshots
        ORDER BY sku
        """.trimIndent(),
        String::class.java
    )
}
