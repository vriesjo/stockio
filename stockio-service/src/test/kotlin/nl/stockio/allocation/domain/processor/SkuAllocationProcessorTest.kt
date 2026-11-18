package nl.stockio.allocation.domain.processor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import nl.stockio.allocation.domain.model.AllocationOrder
import nl.stockio.allocation.domain.performance.AllocationLockContentionPolicy
import nl.stockio.allocation.domain.port.outbound.SkuAllocationDatabase
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.AllocationSkuOperationEntity
import nl.stockio.allocation.infra.DemandOrderLineEntity
import nl.stockio.api.dto.AllocationProcessingOutcome
import nl.stockio.api.dto.AllocationRecord
import nl.stockio.api.dto.AllocationStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono

class SkuAllocationProcessorTest {
    private val database = mockk<SkuAllocationDatabase>()
    private val transactions = mockk<TransactionalOperator>()
    private val lockContention = mockk<AllocationLockContentionPolicy>()
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val processor = SkuAllocationProcessor(
        database, transactions, lockContention,
        Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC), mapper
    )
    private val runId = UUID.randomUUID()
    private val today = LocalDate.of(2026, 11, 9)
    private val order = AllocationOrder(
        orderId = "order-1", orderLineId = "line-1", customer = "Northwind", sku = "SKU",
        quantity = 2, priority = 1, placedDate = today, requiredDeliveryDate = today
    )

    @BeforeEach
    fun openTheSkuTransaction() {
        every { transactions.transactional(any<Mono<List<AllocationRecord>>>()) } answers { firstArg() }
        every { lockContention.applyReactive() } returns Mono.empty()
        every { database.lockSku("SKU") } returns Mono.empty()
    }

    @Test
    fun `returns a cached sku result without recalculating`() {
        val cached = listOf(record("line-1"))
        every { database.findOperation(runId, "SKU") } returns Mono.just(
            AllocationSkuOperationEntity(
                runId = runId, sku = "SKU", requestHash = "hash", resultJson = mapper.writeValueAsString(cached)
            )
        )

        processor.process(runId, "hash", "SKU", today, listOf(order)).block() shouldBe cached

        verify(exactly = 0) { database.findClaims(any()) }
        verify(exactly = 0) { database.findLines(any()) }
        verify(exactly = 0) { database.lockStock(any()) }
        verify(exactly = 0) { database.lockUnreceivedSupply(any()) }
    }

    @Test
    fun `rejects an order owned by another run`() {
        every { database.findOperation(runId, "SKU") } returns Mono.empty()
        every { database.completedAllocations(runId, "SKU") } returns Mono.empty()
        every { database.findClaims(listOf("order-1")) } returns Mono.just(listOf(claim(runId = UUID.randomUUID())))
        every { database.findLines(listOf("line-1")) } returns Mono.just(listOf(storedLine()))

        shouldThrow<IllegalArgumentException> {
            processor.process(runId, "hash", "SKU", today, listOf(order)).block()
        }.message shouldBe "Order 'order-1' belongs to another run."

        verify(exactly = 0) { database.lockStock(any()) }
    }

    @Test
    fun `rejects an order line that does not match its registered document`() {
        every { database.findOperation(runId, "SKU") } returns Mono.empty()
        every { database.completedAllocations(runId, "SKU") } returns Mono.empty()
        every { database.findClaims(listOf("order-1")) } returns Mono.just(listOf(claim(runId)))
        every { database.findLines(listOf("line-1")) } returns Mono.just(listOf(storedLine(quantity = 9)))

        shouldThrow<IllegalArgumentException> {
            processor.process(runId, "hash", "SKU", today, listOf(order)).block()
        }.message shouldBe "Order line 'line-1' does not match its registered document."

        verify(exactly = 0) { database.lockStock(any()) }
    }

    private fun claim(runId: UUID) = AllocationOrderClaimEntity(
        orderId = "order-1", runId = runId, customer = "Northwind", priority = 1, placedDate = today,
        reservationExpiresAt = Instant.parse("2026-11-09T13:00:00Z")
    )

    private fun storedLine(quantity: Int = 2) = DemandOrderLineEntity(
        orderLineId = "line-1", orderId = "order-1", sku = "SKU", requestedQuantity = quantity,
        requiredDeliveryDate = today
    )

    private fun record(lineId: String) = AllocationRecord(
        orderId = "order-1", orderLineId = lineId, customer = "Northwind", sku = "SKU", priority = 1,
        placedDate = today, requiredDeliveryDate = today, requestedQuantity = 2,
        allocatedFromInventory = 2, allocatedFromInbound = 0, backorderedQuantity = 0,
        status = AllocationStatus.ALLOCATED, sources = emptyList(),
        processingOutcome = AllocationProcessingOutcome.PROCESSED
    )
}
