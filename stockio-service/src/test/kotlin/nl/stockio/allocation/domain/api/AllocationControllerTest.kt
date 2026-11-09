package nl.stockio.allocation.domain.api

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
import nl.stockio.allocation.domain.port.AllocationGateway
import nl.stockio.allocation.domain.job.AllocationJobExecutor
import nl.stockio.api.dto.AllocationJobAccepted
import nl.stockio.api.dto.AllocationJobStatus
import nl.stockio.allocation.domain.service.AllocationJobRunrService
import nl.stockio.stock.domain.service.StockLedgerService
import nl.stockio.stock.domain.service.StockLedgerSyncStatus
import nl.stockio.allocation.domain.api.exception.CustomerExceptionHandler
import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.allocation.domain.performance.AllocationLockContentionException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

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
        val mockMvc = createMockMvc(
            executor = executor,
            jobRunrService = mock(AllocationJobRunrService::class.java),
            stockLedgerService = mock(StockLedgerService::class.java)
        )

        mockMvc.perform(
            post("/api/allocation/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isAccepted)
            .andExpect(header().string("Location", "/api/allocation/jobs/${accepted.jobId}"))
            .andExpect(jsonPath("$.jobId").value(accepted.jobId.toString()))

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

        val mockMvc = createMockMvc(
            executor = mock(AllocationJobExecutor::class.java),
            jobRunrService = jobRunrService,
            stockLedgerService = stockLedgerService
        )

        mockMvc.perform(get("/api/allocation/jobs/$jobId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SCHEDULED"))
        mockMvc.perform(get("/api/allocation/stock-ledger/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.skuCount").value(3))

        verify(jobRunrService).getStatusById(jobId)
        verify(stockLedgerService).getStatusLastSyncRun()
    }

    private fun createMockMvc(
        executor: AllocationJobExecutor,
        jobRunrService: AllocationJobRunrService,
        stockLedgerService: StockLedgerService,
        provisional: AllocationGateway = DisabledAllocationRedisGateway()
    ): MockMvc =
        MockMvcBuilders.standaloneSetup(
            AllocationController(
                allocationJobExecutor = executor,
                allocationJobRunrService = jobRunrService,
                ledgerSyncStatusService = stockLedgerService,
                clock = clock,
                allocationRedisGateway = provisional
            )
        ).setControllerAdvice(CustomerExceptionHandler()).build()

    @Test
    fun `inline capacity rejection returns 503 rather than acceptance or async fallback`() {
        val request = AllocationRequest(UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND)
        val executor = io.mockk.mockk<AllocationJobExecutor>()
        io.mockk.every { executor.executeSync(any(), request) } throws AllocationCapacityExceededException()
        val mvc = createMockMvc(executor, mock(AllocationJobRunrService::class.java),
            mock(StockLedgerService::class.java))
        mvc.perform(post("/api/allocation/jobs?async=false").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("ALLOCATION_CAPACITY_EXHAUSTED"))
            .andExpect(header().doesNotExist("Location"))
        io.mockk.verify(exactly = 0) { executor.executeAsync(any()) }
    }

    @Test
    fun `inline lock contention rejection returns 503 rather than acceptance or async fallback`() {
        val request = AllocationRequest(UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND)
        val executor = io.mockk.mockk<AllocationJobExecutor>()
        io.mockk.every { executor.executeSync(any(), request) } throws AllocationLockContentionException()
        val mvc = createMockMvc(executor, mock(AllocationJobRunrService::class.java),
            mock(StockLedgerService::class.java))
        mvc.perform(post("/api/allocation/jobs?async=false").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("ALLOCATION_LOCK_CONTENTION"))
            .andExpect(header().doesNotExist("Location"))
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
        val mvc = createMockMvc(
            executor,
            mock(AllocationJobRunrService::class.java),
            mock(StockLedgerService::class.java),
            gateway
        )

        mvc.perform(
            post("/api/allocation/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isAccepted)

        org.junit.jupiter.api.Assertions.assertTrue(gateway.acceptedRequest)
        io.mockk.verify(exactly = 1) { executor.executeAsync(request) }
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
        val mvc = createMockMvc(
            io.mockk.mockk<AllocationJobExecutor>(relaxed = true),
            mock(AllocationJobRunrService::class.java),
            mock(StockLedgerService::class.java),
            gateway
        )
        mvc.perform(
            post("/api/allocation/jobs?async=false")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("FAST_PATH_SYNC_DISABLED"))
    }
}
