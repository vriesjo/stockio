package nl.stockio.allocation.infra.inbound

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.DemandOrder
import nl.stockio.api.dto.DemandOrderLine
import nl.stockio.allocation.infra.DisabledAllocationRedisGateway
import nl.stockio.allocation.domain.port.outbound.AllocationGateway
import nl.stockio.allocation.domain.job.AllocationJobExecutor
import nl.stockio.api.dto.AllocationJobAccepted
import nl.stockio.api.dto.AllocationJobStatus
import nl.stockio.allocation.application.job.AllocationJobRunrService
import nl.stockio.stock.domain.service.StockLedgerService
import nl.stockio.stock.domain.service.StockLedgerSyncStatus
import nl.stockio.allocation.infra.inbound.exception.CustomerExceptionHandler
import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.allocation.domain.performance.AllocationLockContentionException
import nl.stockio.allocation.infra.inbound.AllocationController
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class AllocationControllerTest {

    private val today = LocalDate.parse("2026-03-03")
    private val now = Instant.parse("2026-03-03T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Test
    fun `generated interface maps allocation submission and preserves async default and location`() {
        val request = AllocationRequest(
            requestId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            asOfDate = today,
            runType = AllocationRunType.NEW_DEMAND
        )
        val accepted = AllocationJobAccepted(
            jobId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            requestId = request.requestId
        )
        val executor = mock(AllocationJobExecutor::class.java)
        `when`(executor.executeAsync(request)).thenReturn(accepted)
        val client = createClient(
            executor = executor,
            jobRunrService = mock(AllocationJobRunrService::class.java),
            stockLedgerService = mock(StockLedgerService::class.java)
        )

        client.post()
            .uri("/api/allocation/jobs")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(objectMapper.writeValueAsString(request))
            .exchange()
            .expectStatus().isAccepted
            .expectHeader().valueEquals(HttpHeaders.LOCATION, "/api/allocation/jobs/${accepted.jobId}")
            .expectBody()
            .jsonPath("$.jobId").isEqualTo(accepted.jobId.toString())

        verify(executor).executeAsync(request)
    }

    @Test
    fun `generated interface maps all allocation status routes`() {
        val jobId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val jobStatus = AllocationJobStatus(jobId, "SCHEDULED")
        val jobRunrService = mock(AllocationJobRunrService::class.java)
        `when`(jobRunrService.getStatusById(jobId)).thenReturn(jobStatus)

        val stockStatus = StockLedgerSyncStatus(
            schedulingEnabled = true,
            lastSuccessAt = now,
            snapshotDate = today,
            skuCount = 3,
            lotSnapshotCount = 4,
            nextExpectedAt = now.plusSeconds(3600),
            staleAfter = now.plusSeconds(7200),
            stale = false
        )
        val stockLedgerService = mock(StockLedgerService::class.java)
        `when`(stockLedgerService.getStatusLastSyncRun()).thenReturn(stockStatus)

        val client = createClient(
            executor = mock(AllocationJobExecutor::class.java),
            jobRunrService = jobRunrService,
            stockLedgerService = stockLedgerService
        )

        client.get().uri("/api/allocation/jobs/$jobId")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("SCHEDULED")
        client.get().uri("/api/allocation/stock-ledger/status")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.skuCount").isEqualTo(3)

        verify(jobRunrService).getStatusById(jobId)
        verify(stockLedgerService).getStatusLastSyncRun()
    }

    private fun createClient(
        executor: AllocationJobExecutor,
        jobRunrService: AllocationJobRunrService,
        stockLedgerService: StockLedgerService,
        provisional: AllocationGateway = DisabledAllocationRedisGateway()
    ): WebTestClient =
        WebTestClient.bindToController(
            AllocationController(
                allocationJobExecutor = executor,
                allocationJobRunrService = jobRunrService,
                ledgerSyncStatusService = stockLedgerService,
                clock = clock,
                allocationRedisGateway = provisional
            )
        ).controllerAdvice(CustomerExceptionHandler()).build()

    @Test
    fun `inline capacity rejection returns 503 rather than acceptance or async fallback`() {
        val request = AllocationRequest(UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND)
        val executor = io.mockk.mockk<AllocationJobExecutor>()
        io.mockk.coEvery { executor.execute(any(), request) } throws AllocationCapacityExceededException()
        val client = createClient(executor, mock(AllocationJobRunrService::class.java),
            mock(StockLedgerService::class.java))
        client.post().uri("/api/allocation/jobs?async=false")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(objectMapper.writeValueAsString(request))
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().doesNotExist(HttpHeaders.LOCATION)
            .expectBody()
            .jsonPath("$.code").isEqualTo("ALLOCATION_CAPACITY_EXHAUSTED")
        io.mockk.verify(exactly = 0) { executor.executeAsync(any()) }
    }

    @Test
    fun `inline lock contention rejection returns 503 rather than acceptance or async fallback`() {
        val request = AllocationRequest(UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND)
        val executor = io.mockk.mockk<AllocationJobExecutor>()
        io.mockk.coEvery { executor.execute(any(), request) } throws AllocationLockContentionException()
        val client = createClient(executor, mock(AllocationJobRunrService::class.java),
            mock(StockLedgerService::class.java))
        client.post().uri("/api/allocation/jobs?async=false")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(objectMapper.writeValueAsString(request))
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().doesNotExist(HttpHeaders.LOCATION)
            .expectBody()
            .jsonPath("$.code").isEqualTo("ALLOCATION_LOCK_CONTENTION")
        io.mockk.verify(exactly = 0) { executor.executeAsync(any()) }
    }

    @Test
    fun `fast path holds in redis then enqueues without inline execution`() {
        val request = AllocationRequest(
            requestId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            asOfDate = today,
            runType = AllocationRunType.NEW_DEMAND,
            demandOrders = listOf(
                DemandOrder(
                    orderId = "order-1",
                    customer = "Northwind",
                    priority = 1,
                    placedDate = today,
                    lines = listOf(DemandOrderLine("line-1", "SKU-1", 1, today))
                )
            )
        )
        val accepted = AllocationJobAccepted(
            jobId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            requestId = request.requestId
        )
        val gateway = object : AllocationGateway {
            override val enabled = true
            var acceptedRequest = false
            override fun accept(request: AllocationRequest) { acceptedRequest = true }
            override fun rollback(request: AllocationRequest) = Unit
            override fun settle(request: AllocationRequest) = Unit
            override fun getByKey(key: String) = null
        }
        val executor = io.mockk.mockk<AllocationJobExecutor>()
        io.mockk.every { executor.executeAsync(request) } returns accepted
        val client = createClient(
            executor,
            mock(AllocationJobRunrService::class.java),
            mock(StockLedgerService::class.java),
            gateway
        )

        client.post()
            .uri("/api/allocation/jobs")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(objectMapper.writeValueAsString(request))
            .exchange()
            .expectStatus().isAccepted

        org.junit.jupiter.api.Assertions.assertTrue(gateway.acceptedRequest)
        io.mockk.verify(exactly = 1) { executor.executeAsync(request) }
        io.mockk.coVerify(exactly = 0) { executor.execute(any(), any()) }
        io.mockk.verify(exactly = 0) { executor.executeSync(any(), any()) }
    }

    @Test
    fun `fast path rejects inline allocation`() {
        val request = AllocationRequest(UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND)
        val gateway = object : AllocationGateway {
            override val enabled = true
            override fun accept(request: AllocationRequest) = Unit
            override fun rollback(request: AllocationRequest) = Unit
            override fun settle(request: AllocationRequest) = Unit
            override fun getByKey(key: String) = null
        }
        val client = createClient(
            io.mockk.mockk<AllocationJobExecutor>(relaxed = true),
            mock(AllocationJobRunrService::class.java),
            mock(StockLedgerService::class.java),
            gateway
        )
        client.post()
            .uri("/api/allocation/jobs?async=false")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(objectMapper.writeValueAsString(request))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("REDIS_SYNC_DISABLED")
    }
}
