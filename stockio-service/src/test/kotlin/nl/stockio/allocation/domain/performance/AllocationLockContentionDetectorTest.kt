package nl.stockio.allocation.domain.performance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
            IllegalStateException("wrapper", SQLException("deadlock", "40P01"))
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
            IllegalStateException("wrapper", SQLException("duplicate", "23505"))
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
}
