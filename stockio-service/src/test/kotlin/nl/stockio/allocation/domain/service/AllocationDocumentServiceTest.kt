package nl.stockio.allocation.domain.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import nl.stockio.allocation.domain.performance.AllocationLockContentionPolicy
import nl.stockio.allocation.domain.port.AllocationDocumentLockRepository
import nl.stockio.allocation.domain.port.AllocationOrderClaimRepository
import nl.stockio.allocation.domain.port.AllocationRunRepository
import nl.stockio.allocation.domain.port.AllocationSkuLockRepository
import nl.stockio.allocation.domain.port.DemandOrderLineRepository
import nl.stockio.allocation.infra.AllocationDocumentLockEntity
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.AllocationRunEntity
import nl.stockio.allocation.infra.DemandOrderLineEntity
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.DemandOrder
import nl.stockio.api.dto.DemandOrderLine
import nl.stockio.reservation.domain.service.ReservationProperties
import nl.stockio.util.requestHash
import org.junit.jupiter.api.Test

class AllocationDocumentServiceTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z")
    private val today = LocalDate.of(2026, 11, 9)
    private val documentLocks = mockk<AllocationDocumentLockRepository>()
    private val skuLocks = mockk<AllocationSkuLockRepository>()
    private val runs = mockk<AllocationRunRepository>()
    private val claims = mockk<AllocationOrderClaimRepository>()
    private val lines = mockk<DemandOrderLineRepository>()
    private val inbound = mockk<InboundShipmentRegistrationService>()
    private val service = AllocationDocumentService(
        documentLocks, skuLocks, runs, claims, lines, inbound,
        mockk<AllocationLockContentionPolicy>(relaxed = true),
        ReservationProperties(ttl = Duration.ofMinutes(30)), Clock.fixed(now, ZoneOffset.UTC)
    )

    @Test
    fun `skips registration when the run is already initialized`() {
        val request = request()
        val run = storedRun(request, documentsInitialized = true)
        every { documentLocks.insertIfMissing(any()) } returns 1
        every { documentLocks.lockByKey(any()) } returns AllocationDocumentLockEntity("order:order-1")
        every { runs.lockById(run.id) } returns run

        service.initialize(run.id, request)

        verify { documentLocks.lockByKey("order:order-1") }
        verify { claims wasNot Called }
        verify { inbound wasNot Called }
    }

    @Test
    fun `rejects an existing claim whose lines no longer match`() {
        val request = request()
        val run = storedRun(request, documentsInitialized = false)
        allowLocks(run)
        every { claims.findById("order-1") } returns Optional.of(claim(run.id))
        every { lines.findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(listOf("order-1")) } returns listOf(
            DemandOrderLineEntity(
                orderLineId = "line-1", orderId = "order-1", sku = "OTHER", requestedQuantity = 1,
                requiredDeliveryDate = today
            )
        )

        shouldThrow<IllegalArgumentException> { service.initialize(run.id, request) }
            .message shouldBe "Order 'order-1' was retried with different document data."

        verify { inbound wasNot Called }
    }

    @Test
    fun `rejects a new claim whose line ids already belong to another order`() {
        val request = request()
        val run = storedRun(request, documentsInitialized = false)
        allowLocks(run)
        every { claims.findById("order-1") } returns Optional.empty()
        every { lines.findAllById(listOf("line-1")) } returns listOf(
            DemandOrderLineEntity(orderLineId = "line-1", orderId = "other-order", sku = "SKU", requestedQuantity = 1)
        )

        shouldThrow<IllegalArgumentException> { service.initialize(run.id, request) }
            .message shouldBe "Order line IDs already belong to another order."

        verify(exactly = 0) { claims.saveAndFlush(any()) }
        verify { inbound wasNot Called }
    }

    private fun allowLocks(run: AllocationRunEntity) {
        every { documentLocks.insertIfMissing(any()) } returns 1
        every { documentLocks.lockByKey(any()) } returns AllocationDocumentLockEntity("order:order-1")
        every { runs.lockById(run.id) } returns run
    }

    private fun request() = AllocationRequest(
        requestId = UUID.randomUUID(),
        asOfDate = today,
        runType = AllocationRunType.NEW_DEMAND,
        demandOrders = listOf(DemandOrder("order-1", "Northwind", 1, today, listOf(
            DemandOrderLine("line-1", "SKU", 1, today)
        )))
    )

    private fun storedRun(request: AllocationRequest, documentsInitialized: Boolean) = AllocationRunEntity(
        id = UUID.randomUUID(),
        requestId = request.requestId,
        requestHash = requestHash(request),
        runType = request.runType,
        asOfDate = request.asOfDate,
        documentsInitialized = documentsInitialized,
        completed = false
    )

    private fun claim(runId: UUID) = AllocationOrderClaimEntity(
        orderId = "order-1", runId = runId, customer = "Northwind", priority = 1, placedDate = today
    )
}
