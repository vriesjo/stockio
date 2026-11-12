package nl.stockio.allocation.domain.performance

import io.r2dbc.spi.R2dbcException
import jakarta.persistence.LockTimeoutException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionContextManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import reactor.core.publisher.Mono

@ConfigurationProperties("stockio.allocation.lock-contention")
data class AllocationLockContentionProperties(
    val enabled: Boolean = false,
    val lockTimeout: Duration = Duration.ZERO,
    val statementTimeout: Duration = Duration.ZERO
) {
    init {
        if (enabled) {
            require(lockTimeout > Duration.ZERO) { "Enabled lock contention controls require a positive lock timeout." }
            require(statementTimeout > Duration.ZERO) {
                "Enabled lock contention controls require a positive statement timeout."
            }
        }
    }
}

/**
 * Configuration class for allocation lock contention mitigation.
 * This class enables the configuration properties for lock contention and provides a bean for the policy.
 */
@Configuration
@EnableConfigurationProperties(AllocationLockContentionProperties::class)
class AllocationLockContentionConfiguration

class AllocationLockContentionException(
    message: String = "ALLOCATION_LOCK_CONTENTION: Database lock contention exceeded configured wait budget; retry unchanged.",
    cause: Throwable? = null
) : RuntimeException(message, cause)

object AllocationLockContentionDetector {
    // Retryable SQL states for PostgreSQL that indicate lock contention or timeout issues.
    private val retryableSqlStates = setOf("55P03", "57014", "40P01")

    fun isRetryableLockContention(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            // Check for specific exceptions that indicate lock contention or timeout issues.
            when (current) {
                is AllocationLockContentionException -> return true
                is CannotAcquireLockException, is PessimisticLockingFailureException -> return true
                is LockTimeoutException, is QueryTimeoutException, is SQLTimeoutException -> return true
                is SQLException -> if (current.sqlState in retryableSqlStates) return true
                is R2dbcException -> if (current.sqlState?.let { it in retryableSqlStates } == true) return true
            }
            current = current.cause
        }
        return false
    }
}

@Service
class AllocationLockContentionPolicy(
    private val properties: AllocationLockContentionProperties,
    private val jdbcTemplate: JdbcTemplate,
    private val databaseClient: DatabaseClient
) {
    fun apply() {
        if (!properties.enabled) return
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Lock contention policy can only be applied inside an active transaction."
        }
        // Set PostgreSQL session-level lock and statement timeouts to mitigate lock contention.
        // The lock_timeout controls how long a transaction will wait to acquire a lock before failing.
        // The statement_timeout controls the maximum allowed duration of any statement.
        applyLocalTimeouts { statement -> jdbcTemplate.execute(statement) }
    }

    /**
     * Runs the same `SET LOCAL` statements on the R2DBC connection bound to the current reactive transaction.
     * The caller must invoke this before the statement that takes the SKU lock.
     */
    fun applyReactive(): Mono<Void> {
        if (!properties.enabled) return Mono.empty()
        return TransactionContextManager.currentContext()
            .onErrorMap(NoTransactionException::class.java) {
                IllegalStateException("Lock contention policy can only be applied inside an active transaction.")
            }
            .then(Mono.defer { applyLocalTimeoutsReactive() })
    }

    private fun applyLocalTimeouts(execute: (String) -> Unit) {
        execute("SET LOCAL lock_timeout = '${properties.lockTimeout.toMillis()}ms'")
        execute("SET LOCAL statement_timeout = '${properties.statementTimeout.toMillis()}ms'")
    }

    private fun applyLocalTimeoutsReactive(): Mono<Void> =
        databaseClient.sql("SET LOCAL lock_timeout = '${properties.lockTimeout.toMillis()}ms'").then()
            .then(databaseClient.sql("SET LOCAL statement_timeout = '${properties.statementTimeout.toMillis()}ms'").then())
}
