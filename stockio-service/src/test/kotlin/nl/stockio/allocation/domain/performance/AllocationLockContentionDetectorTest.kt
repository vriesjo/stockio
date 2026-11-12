package nl.stockio.allocation.domain.performance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.r2dbc.spi.R2dbcException
import jakarta.persistence.LockTimeoutException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.time.Duration
import org.junit.jupiter.api.Test
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.dao.QueryTimeoutException

class AllocationLockContentionDetectorTest {
    @Test
    fun `treats lock and timeout failures as retryable including a nested cause`() {
        val retryable = listOf(
            AllocationLockContentionException(),
            CannotAcquireLockException("busy"),
            PessimisticLockingFailureException("busy"),
            LockTimeoutException("lock"),
            QueryTimeoutException("timeout"),
            SQLTimeoutException("timeout"),
            SQLException("lock", "55P03"),
            SQLException("cancel", "57014"),
            SQLException("deadlock", "40P01"),
            IllegalStateException("wrapper", SQLException("deadlock", "40P01")),
            SqlStateR2dbcException("55P03"),
            SqlStateR2dbcException("57014"),
            IllegalStateException("wrapper", SqlStateR2dbcException("40P01"))
        )

        retryable.forEach { error ->
            AllocationLockContentionDetector.isRetryableLockContention(error) shouldBe true
        }
    }

    @Test
    fun `ignores unrelated failures and a non retryable sql state`() {
        listOf<Throwable?>(
            null,
            IllegalArgumentException("bad request"),
            SQLException("duplicate", "23505"),
            IllegalStateException("wrapper", SQLException("duplicate", "23505")),
            SqlStateR2dbcException("23505")
        ).forEach { error ->
            AllocationLockContentionDetector.isRetryableLockContention(error) shouldBe false
        }
    }

    @Test
    fun `enabled contention controls require positive lock and statement timeouts`() {
        AllocationLockContentionProperties(enabled = false)

        AllocationLockContentionProperties(
            enabled = true,
            lockTimeout = Duration.ofMillis(1),
            statementTimeout = Duration.ofMillis(1)
        )

        shouldThrow<IllegalArgumentException> {
            AllocationLockContentionProperties(
                enabled = true,
                lockTimeout = Duration.ZERO,
                statementTimeout = Duration.ofMillis(1)
            )
        }.message shouldBe "Enabled lock contention controls require a positive lock timeout."

        shouldThrow<IllegalArgumentException> {
            AllocationLockContentionProperties(
                enabled = true,
                lockTimeout = Duration.ofMillis(1),
                statementTimeout = Duration.ZERO
            )
        }.message shouldBe "Enabled lock contention controls require a positive statement timeout."
    }

    @Test
    fun `reactive policy does nothing when contention control is disabled`() {
        val policy = AllocationLockContentionPolicy(
            AllocationLockContentionProperties(),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )

        policy.applyReactive().block()
    }

    @Test
    fun `reactive policy refuses to run outside a reactive transaction`() {
        val policy = AllocationLockContentionPolicy(
            AllocationLockContentionProperties(
                enabled = true,
                lockTimeout = Duration.ofSeconds(2),
                statementTimeout = Duration.ofSeconds(20)
            ),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )

        shouldThrow<IllegalStateException> { policy.applyReactive().block() }
            .message shouldBe "Lock contention policy can only be applied inside an active transaction."
    }

    private class SqlStateR2dbcException(sqlState: String) : R2dbcException("timeout", sqlState)
}
