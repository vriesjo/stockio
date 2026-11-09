package nl.stockio.allocation.domain.processor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import nl.stockio.allocation.domain.model.AllocationOrder
import nl.stockio.allocation.domain.performance.AllocationLockContentionPolicy
import nl.stockio.allocation.domain.port.AllocationOrderClaimRepository
import nl.stockio.allocation.domain.port.AllocationSkuLockRepository
import nl.stockio.allocation.domain.port.AllocationSkuOperationRepository
import nl.stockio.allocation.domain.port.DemandOrderLineRepository
import nl.stockio.allocation.domain.port.OpenBackorderRepository
import nl.stockio.allocation.domain.service.AllocationRunLifecycleService
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.AllocationSkuLockEntity
import nl.stockio.allocation.infra.AllocationSkuOperationEntity
import nl.stockio.allocation.infra.DemandOrderLineEntity
import nl.stockio.api.dto.AllocationProcessingOutcome
import nl.stockio.api.dto.AllocationRecord
import nl.stockio.api.dto.AllocationStatus
import nl.stockio.reservation.domain.port.InboundReservationRepository
import nl.stockio.reservation.domain.port.InboundSupplyRepository
import nl.stockio.stock.domain.port.StockRepository
import nl.stockio.stock.domain.port.StockReservationRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SkuAllocationProcessorTest {
    private val lifecycle = mockk<AllocationRunLifecycleService>()
    private val skuLocks = mockk<AllocationSkuLockRepository>()
    private val operations = mockk<AllocationSkuOperationRepository>()
    private val claims = mockk<AllocationOrderClaimRepository>()
    private val lines = mockk<DemandOrderLineRepository>()
    private val inboundSupply = mockk<InboundSupplyRepository>()
    private val stock = mockk<StockRepository>()
    private val stockReservations = mockk<StockReservationRepository>()
    private val inboundReservations = mockk<InboundReservationRepository>()
    private val backorders = mockk<OpenBackorderRepository>()
    private val lockContention = mockk<AllocationLockContentionPolicy>()
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val processor = SkuAllocationProcessor(
        lifecycle, skuLocks, operations, claims, lines, inboundSupply, stock, stockReservations,
        inboundReservations, backorders, lockContention,
        Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC), mapper
    )
    private val runId = UUID.randomUUID()
    private val today = LocalDate.of(2026, 11, 9)
    private val order = AllocationOrder(
        orderId = "order-1", orderLineId = "line-1", customer = "Northwind", sku = "SKU",
        quantity = 2, priority = 1, placedDate = today, requiredDeliveryDate = today
    )

    @BeforeEach
    fun lockTheSku() {
        every { lockContention.apply() } just Runs
        every { skuLocks.insertIfMissing("SKU") } returns 1
        every { skuLocks.lockBySku("SKU") } returns AllocationSkuLockEntity("SKU")
    }

    @Test
    fun `returns a cached sku result without recalculating`() {
        val cached = listOf(record("line-1"))
        every { operations.findByRunIdAndSku(runId, "SKU") } returns AllocationSkuOperationEntity(
            runId = runId, sku = "SKU", requestHash = "hash", resultJson = mapper.writeValueAsString(cached)
        )

        processor.process(runId, "hash", "SKU", today, listOf(order)) shouldBe cached

        verify { lifecycle wasNot Called }
        verify { claims wasNot Called }
        verify { lines wasNot Called }
        verify { stock wasNot Called }
        verify { inboundSupply wasNot Called }
    }

    @Test
    fun `rejects an order owned by another run`() {
        every { operations.findByRunIdAndSku(runId, "SKU") } returns null
        every { lifecycle.getRunById(runId) } returns null
        every { claims.findAllById(listOf("order-1")) } returns listOf(claim(runId = UUID.randomUUID()))
        every { lines.findAllById(listOf("line-1")) } returns listOf(storedLine())

        shouldThrow<IllegalArgumentException> {
            processor.process(runId, "hash", "SKU", today, listOf(order))
        }.message shouldBe "Order 'order-1' belongs to another run."

        verify { stock wasNot Called }
    }

    @Test
    fun `rejects an order line that does not match its registered document`() {
        every { operations.findByRunIdAndSku(runId, "SKU") } returns null
        every { lifecycle.getRunById(runId) } returns null
        every { claims.findAllById(listOf("order-1")) } returns listOf(claim(runId))
        every { lines.findAllById(listOf("line-1")) } returns listOf(storedLine(quantity = 9))

        shouldThrow<IllegalArgumentException> {
            processor.process(runId, "hash", "SKU", today, listOf(order))
        }.message shouldBe "Order line 'line-1' does not match its registered document."

        verify { stock wasNot Called }
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
