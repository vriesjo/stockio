package nl.stockio.allocation.domain.processor

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class StockProjectionLockService(
    private val jdbcTemplate: JdbcTemplate
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun acquireAllocationReadLock() {
        checkNotNull(
            jdbcTemplate.queryForObject(
                "SELECT id FROM stock_projection_lock WHERE id = 1 FOR SHARE",
                Int::class.java
            )
        ) {
            "The stock projection lock row is missing."
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun acquire() {
        checkNotNull(
            jdbcTemplate.queryForObject(
                "SELECT id FROM stock_projection_lock WHERE id = 1 FOR UPDATE",
                Int::class.java
            )
        ) {
            "The stock projection lock row is missing."
        }
    }
}
