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
import nl.stockio.api.dto.OrderReservationLine
import nl.stockio.api.dto.OrderReservationResponse
import nl.stockio.api.dto.OrderReservationStatus
import nl.stockio.allocation.domain.job.AllocationJobExecutor
import nl.stockio.api.dto.AllocationJobAccepted
import nl.stockio.api.dto.AllocationJobStatus
import nl.stockio.allocation.domain.service.AllocationJobRunrService
import nl.stockio.reservation.domain.service.OrderReservationService
import nl.stockio.stock.domain.service.StockLedgerService
import nl.stockio.stock.domain.service.StockLedgerSyncStatus
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
            orderReservationService = mock(OrderReservationService::class.java),
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

        val reservation = OrderReservationResponse(
            orderId = "ORDER-1",
            lines = listOf(OrderReservationLine("LINE-1", "SKU-1", 2, today)),
            status = OrderReservationStatus.PENDING_CONFIRMATION,
            createdAt = now,
            expiresAt = now.plusSeconds(900),
            confirmedAt = null,
            expiredAt = null,
            cleanupPending = false
        )
        val reservationService = mock(OrderReservationService::class.java)
        `when`(reservationService.getOrderReservationStatus("ORDER-1")).thenReturn(reservation)
        `when`(reservationService.getOrderReservationStatus("MISSING")).thenReturn(null)

        val mockMvc = createMockMvc(
            executor = mock(AllocationJobExecutor::class.java),
            jobRunrService = jobRunrService,
            orderReservationService = reservationService,
            stockLedgerService = stockLedgerService
        )

        mockMvc.perform(get("/api/allocation/jobs/$jobId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SCHEDULED"))
        mockMvc.perform(get("/api/allocation/stock-ledger/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.skuCount").value(3))
        mockMvc.perform(get("/api/allocation/order-reservation/ORDER-1/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
        mockMvc.perform(get("/api/allocation/order-reservation/MISSING/status"))
            .andExpect(status().isNotFound)

        verify(jobRunrService).getStatusById(jobId)
        verify(stockLedgerService).getStatusLastSyncRun()
        verify(reservationService).getOrderReservationStatus("ORDER-1")
        verify(reservationService).getOrderReservationStatus("MISSING")
    }

    private fun createMockMvc(
        executor: AllocationJobExecutor,
        jobRunrService: AllocationJobRunrService,
        orderReservationService: OrderReservationService,
        stockLedgerService: StockLedgerService
    ): MockMvc =
        MockMvcBuilders.standaloneSetup(
            AllocationController(
                allocationJobExecutor = executor,
                allocationJobRunrService = jobRunrService,
                orderReservationService = orderReservationService,
                ledgerSyncStatusService = stockLedgerService,
                clock = clock
            )
        ).build()
}
