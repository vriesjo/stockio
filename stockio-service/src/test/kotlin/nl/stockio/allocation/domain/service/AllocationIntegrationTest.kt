package nl.stockio.allocation.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.DemandOrder
import nl.stockio.api.dto.DemandOrderLine
import nl.stockio.api.dto.InboundShipment
import nl.stockio.api.dto.InboundShipmentLine
import nl.stockio.api.dto.ProcessDailyStockRequest
import nl.stockio.api.dto.OrderReservationStatus
import nl.stockio.allocation.domain.job.AllocationJobExecutor
import nl.stockio.allocation.domain.job.AllocationJobTrackingService
import nl.stockio.allocation.domain.job.AllocationJobTrackingState
import nl.stockio.api.dto.AllocationCompletionResult
import nl.stockio.api.dto.AllocationJobAccepted
import nl.stockio.api.dto.AllocationProcessingOutcome
import nl.stockio.api.dto.AllocationRecord
import nl.stockio.api.dto.AllocationStatus
import nl.stockio.allocation.domain.processor.AllocationProcessor
import nl.stockio.allocation.domain.processor.AllocationProjectionException
import nl.stockio.stock.domain.job.StockLedgerSyncJob
import nl.stockio.stock.infra.AllocationJobResultEntity
import nl.stockio.stock.infra.AllocationJobResultJpaRepository
import nl.stockio.stock.infra.StockLedgerSyncStatusJdbcTemplateRepository
import nl.stockio.reservation.domain.job.ReservationExpiryJob
import nl.stockio.allocation.domain.processor.ExcelReportProcessor
import nl.stockio.allocation.domain.processor.SkuAllocationProcessor
import nl.stockio.stock.domain.processor.StockLedgerProcessor
import nl.stockio.stock.domain.processor.StockLedgerSkuWorker
import nl.stockio.stock.domain.port.StockLedgerRunRepository
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import nl.stockio.allocation.infra.AllocationRecordJpaRepository
import nl.stockio.allocation.infra.AllocationRunJpaRepository
import nl.stockio.allocation.infra.AllocationSourceJpaRepository
import nl.stockio.allocation.infra.AllocationOrderClaimJpaRepository
import nl.stockio.StockioApplication
import nl.stockio.allocation.domain.performance.AllocationAdmission
import nl.stockio.allocation.domain.performance.AllocationAdmissionProperties
import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.reservation.infra.InboundSupplyJpaRepository
import nl.stockio.allocation.infra.OpenBackorderJpaRepository
import nl.stockio.stock.infra.StockSnapshotRepositoryJdbcTemplate
import nl.stockio.stock.infra.StockEntity
import nl.stockio.stock.infra.StockProjectionLockJpaRepository
import nl.stockio.stock.infra.StockJpaRepository
import nl.stockio.stock.infra.StockReservationJpaRepository
import nl.stockio.util.requestHash
import nl.stockio.product.infra.ProductKnowledgeChunk
import nl.stockio.product.infra.ProductVectorJdbcTemplateRepository
import nl.stockio.reservation.domain.service.ReservationExpiryService
import nl.stockio.reservation.infra.InboundSupplyEntity
import nl.stockio.stock.domain.service.StockLedgerSyncProperties
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.awaitility.Awaitility
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.jobrunr.jobs.states.StateName
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.storage.StorageProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest(
    classes = [StockioApplication::class],
    properties = [
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "stockio.allocation.kafka.enabled=false",
        "jobrunr.dashboard.enabled=false",
        "stockio.allocation.ledger-sync.enabled=false",
        "stockio.allocation.reservation.expiry-interval=1d",
        "stockio.allocation.reservation.expiry-batch-size=2"
    ]
)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AllocationIntegrationTest {

    @MockitoBean
    private lateinit var clock: Clock

    @Autowired
    private lateinit var claimRepository: AllocationOrderClaimJpaRepository

    @Autowired
    private lateinit var inboundSupplyRepository: InboundSupplyJpaRepository

    @Autowired
    private lateinit var expiryService: ReservationExpiryService

    @Autowired
    private lateinit var expiryJob: ReservationExpiryJob

    @Autowired
    private lateinit var jobScheduler: JobScheduler

    @Autowired
    private lateinit var jobStorage: StorageProvider

    @Autowired
    private lateinit var allocationService: AllocationService

    @Autowired
    private lateinit var allocationBackorderService: AllocationBackorderService

    @Autowired
    private lateinit var allocationJobExecutor: AllocationJobExecutor

    @Autowired
    private lateinit var allocationJobTracking: AllocationJobTrackingService

    @Autowired
    private lateinit var processingService: AllocationProcessor

    @Autowired
    private lateinit var ledgerSyncJob: StockLedgerSyncJob

    @Autowired
    private lateinit var stockLedgerSyncStatusRepository: StockLedgerSyncStatusJdbcTemplateRepository

    @Autowired
    private lateinit var jobResultRepository: AllocationJobResultJpaRepository

    @Autowired
    private lateinit var skuAllocationProcessor: SkuAllocationProcessor

    @Autowired
    private lateinit var allocationRunLifecycleService: AllocationRunLifecycleService

    @Autowired
    private lateinit var documentService: AllocationDocumentService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    private lateinit var reportGenerator: ExcelReportProcessor

    @Autowired
    private lateinit var sourceRepository: AllocationSourceJpaRepository

    @Autowired
    private lateinit var recordRepository: AllocationRecordJpaRepository

    @Autowired
    private lateinit var runRepository: AllocationRunJpaRepository

    @Autowired
    private lateinit var backorderRepository: OpenBackorderJpaRepository

    @Autowired
    private lateinit var snapshotRepository: StockSnapshotRepositoryJdbcTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var stockRepository: StockJpaRepository

    @Autowired
    private lateinit var stockReservationRepository: StockReservationJpaRepository

    @Autowired
    private lateinit var productVectorRepository: ProductVectorJdbcTemplateRepository

    @Autowired
    private lateinit var stockLedgerProcessor: StockLedgerProcessor

    @Autowired
    private lateinit var stockLedgerSkuWorker: StockLedgerSkuWorker

    @Autowired
    private lateinit var ledgerRunRepository: StockLedgerRunRepository

    @Autowired
    private lateinit var stockProjectionProcessor: StockProjectionProcessor

    @Autowired
    private lateinit var stockProjectionLockRepository: StockProjectionLockJpaRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var allocationExcelService: AllocationExcelService

    @Autowired
    private lateinit var mockMvc: MockMvc

    @TempDir
    lateinit var temporaryDirectory: Path

    @BeforeEach
    fun clearDatabase() {
        Mockito.`when`(clock.zone).thenReturn(ZoneId.systemDefault())
        setNow(Instant.now().truncatedTo(ChronoUnit.MICROS))
        jdbcTemplate.update("UPDATE stock_ledger_coordinator SET run_id = NULL, owner = NULL, lease_until = NULL WHERE id = 1")
        jdbcTemplate.update("DELETE FROM stock_receipt_followup")
        jdbcTemplate.update("DELETE FROM stock_ledger_work")
        jdbcTemplate.update("DELETE FROM stock_ledger_run")
        jdbcTemplate.update(
            """UPDATE stock_ledger_sync_status SET last_success_at = NULL, snapshot_date = NULL,
                sku_count = NULL, lot_snapshot_count = NULL, monitoring_started_at = CURRENT_TIMESTAMP WHERE id = 1"""
        )
        jobResultRepository.deleteAll()
        jdbcTemplate.update("DELETE FROM stock_daily_lot_snapshots")
        jdbcTemplate.update("DELETE FROM stock_daily_snapshots")
        jdbcTemplate.update("DELETE FROM inbound_reservation")
        jdbcTemplate.update("DELETE FROM inbound_supply")
        jdbcTemplate.update("DELETE FROM inbound_shipment")
        stockReservationRepository.deleteAll()
        stockRepository.deleteAll()
        sourceRepository.deleteAll()
        recordRepository.deleteAll()
        backorderRepository.deleteAll()
        jdbcTemplate.update("DELETE FROM demand_order_line")
        jdbcTemplate.update("DELETE FROM demand_order")
        jdbcTemplate.update("DELETE FROM allocation_document_lock")
        jdbcTemplate.update("DELETE FROM product_knowledge_chunks")
        jdbcTemplate.update("DELETE FROM product_embeddings")
        runRepository.deleteAll()
    }

    @Test
    fun `projection lock repository requires an existing transaction`() {
        Assertions.assertThrows(IllegalTransactionStateException::class.java) {
            stockProjectionLockRepository.acquireAllocationReadLock()
        }
        Assertions.assertThrows(IllegalTransactionStateException::class.java) {
            stockProjectionLockRepository.acquire()
        }
    }

    @Test
    fun `projection lock repository rejects a missing lock row`() {
        val exception = Assertions.assertThrows(InvalidDataAccessApiUsageException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbcTemplate.update("DELETE FROM stock_projection_lock WHERE id = 1")
                stockProjectionLockRepository.acquire()
            }
        }
        Assertions.assertEquals("The stock projection lock row is missing.", exception.message)
    }

    @Test
    fun `projection lock repository allows concurrent readers but blocks an exclusive writer`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            TransactionTemplate(transactionManager).executeWithoutResult {
                stockProjectionLockRepository.acquireAllocationReadLock()
                executor.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        stockProjectionLockRepository.acquireAllocationReadLock()
                    }
                }.get(10, TimeUnit.SECONDS)
                executor.submit {
                    Assertions.assertThrows(PessimisticLockingFailureException::class.java) {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            jdbcTemplate.execute("SET LOCAL lock_timeout = '200ms'")
                            stockProjectionLockRepository.acquire()
                        }
                    }
                }.get(10, TimeUnit.SECONDS)
            }
            TransactionTemplate(transactionManager).executeWithoutResult {
                stockProjectionLockRepository.acquire()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `flyway seeds active products with generated SKUs`() {
        val productCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Int::class.java)
            ?: error("Flyway product count query returned no result.")
        Assertions.assertEquals(32, productCount)
        Assertions.assertEquals(
            295L,
            jdbcTemplate.queryForObject(
                "SELECT price_cents FROM products WHERE sku = ?",
                Long::class.java,
                "OZEKI-SAKE-DRY"
            ) ?: error("The seeded Ozeki product has no price.")
        )
        Assertions.assertEquals(
            "180:ml",
            jdbcTemplate.queryForObject(
                "SELECT concat(attributes ->> 'content', ':', attributes ->> 'unit') FROM products WHERE sku = ?",
                String::class.java,
                "OZEKI-SAKE-DRY"
            ) ?: error("The seeded Ozeki product has no content attributes.")
        )
    }

    @Test
    fun `pgvector stores and searches catalog embeddings and Tavily knowledge`() {
        val embedding = List(ProductVectorJdbcTemplateRepository.EMBEDDING_DIMENSIONS) { index ->
            if (index == 0) 1.0 else 0.0
        }
        productVectorRepository.upsertProductEmbedding(
            sku = "MAKI-KOMKOMMER",
            model = "integration-test",
            contentHash = "catalog-hash",
            embedding = embedding
        )
        productVectorRepository.upsertKnowledgeChunks(
            sku = "MAKI-KOMKOMMER",
            model = "integration-test",
            chunks = listOf(
                ProductKnowledgeChunk(
                    sourceUrl = "https://example.test/maki-komkommer",
                    sourceTitle = "Maki Komkommer",
                    content = "Cucumber maki sushi",
                    contentHash = "source-hash",
                    embedding = embedding
                )
            )
        )
        productVectorRepository.upsertProductEmbedding(
            sku = "MAKI-KOMKOMMER",
            model = "integration-test",
            contentHash = "catalog-hash",
            embedding = embedding
        )
        productVectorRepository.upsertKnowledgeChunks(
            sku = "MAKI-KOMKOMMER",
            model = "integration-test",
            chunks = listOf(
                ProductKnowledgeChunk(
                    sourceUrl = "https://example.test/maki-komkommer",
                    sourceTitle = "Maki Komkommer",
                    content = "Cucumber maki sushi",
                    contentHash = "source-hash",
                    embedding = embedding
                )
            )
        )

        Assertions.assertEquals(
            "MAKI-KOMKOMMER",
            productVectorRepository.findSimilarProducts(embedding, "integration-test", 1).single().sku
        )
        Assertions.assertEquals(
            "MAKI-KOMKOMMER",
            productVectorRepository.findSimilarKnowledge(embedding, "integration-test", 1).single().sku
        )
        Assertions.assertEquals(
            mapOf("MAKI-KOMKOMMER" to "catalog-hash"),
            productVectorRepository.findProductEmbeddingHashes("integration-test")
        )
        Assertions.assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_knowledge_chunks WHERE sku = ?",
                Int::class.java,
                "MAKI-KOMKOMMER"
            ) ?: error("The product knowledge chunk count query returned no result.")
        )
    }

    @Test
    fun `CSV loader maps sales and deliveries to an allocation request`() {
        val csvPath = temporaryDirectory.resolve("allocations.csv")
        Files.writeString(
            csvPath,
            """
                Artikel,Transactie_type,QTY,Datum
                Maki Zalm,Verkoop,327,1-1-2024
                Maki Zalm,Levering,376,1-1-2024
                Nori,Voorraad,497,2024-01-01
            """.trimIndent()
        )

        val request = allocationExcelService.loadCsvAndCreateAllocationRequest(csvPath        )

        Assertions.assertEquals(LocalDate.now(), request.asOfDate)
        Assertions.assertEquals("MAKI-ZALM", request.demandOrders.single().lines.single().sku)
        Assertions.assertEquals(327, request.demandOrders.single().lines.single().requestedQuantity)
        Assertions.assertEquals(LocalDate.parse("2024-01-01"), request.demandOrders.single().placedDate)
        Assertions.assertEquals(
            request.demandOrders.single().placedDate,
            request.demandOrders.single().lines.single().requiredDeliveryDate
        )
        Assertions.assertEquals(1, request.demandOrders.single().priority)
        Assertions.assertTrue(request.demandOrders.single().customer.isNotBlank())
        Assertions.assertEquals(
            request.demandOrders.single().orderId,
            UUID.fromString(request.demandOrders.single().orderId).toString()
        )
        Assertions.assertEquals("MAKI-ZALM", request.inboundShipments.single().lines.single().sku)
        Assertions.assertEquals(376, request.inboundShipments.single().lines.single().expectedQuantity)
        Assertions.assertEquals(LocalDate.parse("2024-01-01"), request.inboundShipments.single().lines.single().expectedArrivalDate)
        Assertions.assertEquals("MAIN", request.inboundShipments.single().lines.single().warehouseCode)
        Assertions.assertEquals(
            request.inboundShipments.single().shipmentId,
            UUID.fromString(request.inboundShipments.single().shipmentId).toString()
        )
    }

    @Test
    fun `REST endpoint completes allocation and projection without ledger synchronization`() {
        val today = LocalDate.now()
        stockRepository.save(
            StockEntity(
                sku = "MAKI-ZALM",
                lotId = "API-LOT",
                warehouseCode = "MAIN",
                receivedDate = today.minusDays(1),
                quantityOnHand = 5
            )
        )

        val acceptedResponse = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/allocation/jobs")
                .param("async", "false")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "requestId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                          "asOfDate": "$today",
                          "runType": "NEW_DEMAND",
                          "demandOrders": [
                            {
                              "orderId": "API-ORDER",
                              "customer": "API Customer",
                              "priority": 1,
                              "placedDate": "$today",
                              "lines": [{
                                "orderLineId": "API-LINE",
                                "sku": "MAKI-ZALM",
                                "requestedQuantity": 2,
                                "requiredDeliveryDate": "$today"
                              }]
                            }
                          ]
                        }
                    """.trimIndent()
                )
        )
            .andExpect(MockMvcResultMatchers.status().isAccepted)
            .andReturn()
        val jobId = objectMapper.readValue(
            acceptedResponse.response.contentAsString,
            AllocationJobAccepted::class.java
        ).jobId
        Assertions.assertEquals(
            "/api/allocation/jobs/$jobId",
            acceptedResponse.response.getHeader("Location")
        )

        val completion = jobResultRepository.findAll().single()
        val result = objectMapper.readValue(completion.resultJson, AllocationCompletionResult::class.java)
        Assertions.assertEquals(jobId, result.allocationRun.runId)
        Assertions.assertEquals(2, result.allocationRun.allocations.single().allocatedFromInventory)
        Assertions.assertFalse(completion.resultJson.contains("stockSync"))

        val updatedStock = stockRepository.findAll().single()
        Assertions.assertEquals(5, updatedStock.quantityOnHand)
        Assertions.assertEquals(2, updatedStock.quantityReserved)
        Assertions.assertNull(stockLedgerSyncStatusRepository.getSyncStatus().lastSuccessAt)
        Assertions.assertEquals(2, snapshotRepository.findLotsByDate(today).single().quantityReserved)
        mockMvc.perform(MockMvcRequestBuilders.get("/api/reservations/orders/API-ORDER/status"))
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("PENDING_CONFIRMATION"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.cleanupPending").value(false))
    }

    @Test
    fun `REST endpoint rejects non-today allocation before creating a run`() {
        val today = LocalDate.now()

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/allocation/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "requestId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                          "asOfDate": "${today.plusDays(1)}",
                          "runType": "NEW_DEMAND",
                          "demandOrders": [
                            {
                              "orderId": "FUTURE-API-ORDER",
                              "customer": "API Customer",
                              "priority": 1,
                              "placedDate": "$today",
                              "lines": [{
                                "orderLineId": "FUTURE-API-LINE",
                                "sku": "MAKI-ZALM",
                                "requestedQuantity": 1,
                                "requiredDeliveryDate": "${today.plusDays(1)}"
                              }]
                            }
                          ]
                        }
                    """.trimIndent()
                )
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest)
            .andExpect(MockMvcResultMatchers.jsonPath("$.error").value("Allocation requests on this endpoint require asOfDate to be today."))

        Assertions.assertEquals(0, runRepository.count())
    }

    @Test
    fun `allocation job reserves and projects arrived supply without receiving it`() {
        val today = LocalDate.now()
        val request = reservationRequest("ASYNC-LEDGER-ORDER", 3).copy(
            inboundShipments = listOf(singleLineShipment("ASYNC-LEDGER-SHIPMENT", "MAKI-ZALM", 5, today))
        )
        val result = allocationJobExecutor.executeSync(request.requestId, request)
        Assertions.assertEquals(3, result.allocationRun.allocations.single().allocatedFromInbound)
        Assertions.assertTrue(stockRepository.findAll().isEmpty())
        Assertions.assertFalse(inboundSupplyRepository.findAll().single().received)
        Assertions.assertEquals(5, snapshotRepository.findDailyByDate(today).single().closingStock)
        Assertions.assertNull(stockLedgerSyncStatusRepository.getSyncStatus().lastSuccessAt)
        Assertions.assertEquals(result, allocationJobExecutor.executeSync(request.requestId, request))
        Assertions.assertEquals(3, inboundSupplyRepository.findAll().single().quantityReserved)

        ledgerSyncJob.execute()
        Assertions.assertTrue(inboundSupplyRepository.findAll().single().received)
        Assertions.assertEquals(5, stockRepository.findAll().single().quantityOnHand)
        Assertions.assertEquals(3, stockRepository.findAll().single().quantityReserved)
        Assertions.assertEquals(1, stockReservationRepository.count())
        mockMvc.perform(MockMvcRequestBuilders.get("/api/stock/ledger/status"))
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.snapshotDate").value(today.toString()))
            .andExpect(MockMvcResultMatchers.jsonPath("$.lastSuccessAt").exists())
            .andExpect(MockMvcResultMatchers.jsonPath("$.lotSnapshotCount").value(1))
    }

    @Test
    fun `concurrent executions of the same request do not duplicate allocation`() {
        val today = LocalDate.now()
        stockRepository.save(
            StockEntity(sku = "MAKI-ZALM", lotId = "CONCURRENT-REPLAY-STOCK", receivedDate = today, quantityOnHand = 10)
        )
        val request = reservationRequest("CONCURRENT-REPLAY-ORDER", 2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val results = try {
            val attempts = List(2) {
                executor.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    allocationJobExecutor.executeSync(request.requestId, request)
                }
            }
            Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            attempts.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }

        Assertions.assertEquals(results.first(), results.last())
        Assertions.assertEquals(1L, runRepository.count())
        Assertions.assertEquals(1L, jobResultRepository.count())
        Assertions.assertEquals(1L, stockReservationRepository.count())
        Assertions.assertEquals(2, stockRepository.findAll().single().quantityReserved)
    }

    @Test
    fun `allocation projection failure can resume without duplicating committed reservations`() {
        val admissionProperties = AllocationAdmissionProperties(true, 1, 2, 2, 3, 3, 2, 4096)
        val registry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        val admission = AllocationAdmission(admissionProperties, registry)
        val limits = AllocationRequestLimits(admissionProperties, objectMapper, registry)
        val guarded = AllocationProcessor(allocationService, stockProjectionProcessor, allocationBackorderService,
            admission, limits)
        val guardedExecutor = AllocationJobExecutor(
            guarded, jobResultRepository, objectMapper, limits, kafkaTemplate, allocationJobTracking,
            "stockio.allocation.requests"
        )
        val today = LocalDate.now()
        stockRepository.save(
            StockEntity(sku = "MAKI-ZALM", lotId = "ALLOCATION-RETRY-LOT", receivedDate = today, quantityOnHand = 5)
        )
        jdbcTemplate.update("INSERT INTO inbound_shipment (shipment_id) VALUES ('ALLOCATION-RETRY-SHIPMENT')")
        inboundSupplyRepository.save(
            InboundSupplyEntity(
                shipmentId = "ALLOCATION-RETRY-SHIPMENT", shipmentLineId = "ALLOCATION-RETRY-LINE",
                lotId = "ALLOCATION-RETRY-LOT", sku = "MAKI-ZALM", expectedQuantity = 2,
                expectedArrivalDate = today
            )
        )
        val request = reservationRequest("ALLOCATION-RETRY-ORDER", 3)
        Assertions.assertThrows(AllocationProjectionException::class.java) {
            guardedExecutor.executeSync(request.requestId, request)
        }

        Assertions.assertEquals(0.0, registry.get("stockio.allocation.admission.active").gauge().value())
        Assertions.assertEquals(3, stockRepository.findAll().single().quantityReserved)
        Assertions.assertEquals(1, stockReservationRepository.count())
        Assertions.assertFalse(jobResultRepository.existsById(request.requestId))

        inboundSupplyRepository.deleteById("ALLOCATION-RETRY-LINE")
        val result = guardedExecutor.executeSync(request.requestId, request)
        Assertions.assertEquals(3, result.allocationRun.allocations.single().allocatedFromInventory)
        Assertions.assertEquals(3, snapshotRepository.findLotsByDate(today).single().quantityReserved)
        Assertions.assertEquals(3, stockRepository.findAll().single().quantityReserved)
        Assertions.assertEquals(1, stockReservationRepository.count())
        Assertions.assertNull(stockLedgerSyncStatusRepository.getSyncStatus().lastSuccessAt)
    }

    @Test
    fun `Kafka worker tracking starts at processing`() {
        val jobId = UUID.randomUUID()
        val requestId = UUID.randomUUID()

        allocationJobTracking.beginProcessing(jobId, requestId)
        Assertions.assertEquals(
            AllocationJobTrackingState.PROCESSING,
            allocationJobTracking.findById(jobId).orElseThrow().status
        )
        allocationJobTracking.markSucceeded(jobId)
        Assertions.assertEquals(
            AllocationJobTrackingState.SUCCEEDED,
            allocationJobTracking.findById(jobId).orElseThrow().status
        )

        val failedJobId = UUID.randomUUID()
        allocationJobTracking.beginProcessing(failedJobId, requestId)
        allocationJobTracking.markFailed(failedJobId)
        Assertions.assertEquals(
            AllocationJobTrackingState.FAILED,
            allocationJobTracking.findById(failedJobId).orElseThrow().status
        )
    }

    @Test
    fun `saturated admission rejects before persisted run document or reservation writes`() {
        val properties = AllocationAdmissionProperties(true, 1, 1, 1, 1, 1, 1, 4096)
        val registry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        val admission = AllocationAdmission(properties, registry)
        val limits = AllocationRequestLimits(properties, objectMapper, registry)
        val guarded = AllocationProcessor(allocationService, stockProjectionProcessor, allocationBackorderService,
            admission, limits)
        val request = reservationRequest("ADMISSION-REFUSED", 1)
        admission.execute(request.runType) {
            Assertions.assertThrows(AllocationCapacityExceededException::class.java) { guarded.process(request) }
            Assertions.assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM allocation_run WHERE request_id = ?", Long::class.java, request.requestId) ?: -1L)
            Assertions.assertFalse(claimRepository.existsById("ADMISSION-REFUSED"))
            Assertions.assertEquals(0L, stockReservationRepository.count())
            Assertions.assertEquals(0L, backorderRepository.count())
        }
        guarded.process(request)
        Assertions.assertTrue(claimRepository.existsById("ADMISSION-REFUSED"))
    }

    @Test
    fun `cached allocation completion is read without rewriting the result or running ledger`() {
        val request = reservationRequest("CACHED-JOB-RESULT", 1)
        val run = allocationService.allocation(request)
        val completion = AllocationCompletionResult(jobId = request.requestId, allocationRun = run)
        val resultJson = objectMapper.writeValueAsString(completion)
        jobResultRepository.save(AllocationJobResultEntity(jobId = request.requestId, resultJson = resultJson))
        Assertions.assertEquals(completion, allocationJobExecutor.executeSync(request.requestId, request))
        Assertions.assertEquals(resultJson, jobResultRepository.findById(request.requestId).orElseThrow().resultJson)
        Assertions.assertNull(stockLedgerSyncStatusRepository.getSyncStatus().lastSuccessAt)
    }

    @Test
    fun `scheduled sync chooses today's business date again after midnight without new demand`() {
        Mockito.`when`(clock.zone).thenReturn(ZoneId.of("UTC"))
        val firstDate = LocalDate.of(2026, 10, 20)
        setNow(Instant.parse("2026-10-20T23:59:00Z"))
        allocationService.allocation(
            AllocationRequest(
                requestId = UUID.randomUUID(), asOfDate = firstDate, runType = AllocationRunType.NEW_DEMAND,
                inboundShipments = listOf(
                    singleLineShipment("MIDNIGHT-FIRST", "MAKI-ZALM", 2, firstDate),
                    singleLineShipment("MIDNIGHT-SECOND", "MAKI-ZALM", 3, firstDate.plusDays(1))
                )
            )
        )
        ledgerSyncJob.execute()
        Assertions.assertEquals(firstDate, stockLedgerSyncStatusRepository.getSyncStatus().snapshotDate)
        Assertions.assertEquals(2, stockRepository.findAll().sumOf { it.quantityOnHand })

        setNow(Instant.parse("2026-10-21T00:01:00Z"))
        ledgerSyncJob.execute()
        ledgerSyncJob.execute()
        Assertions.assertEquals(firstDate.plusDays(1), stockLedgerSyncStatusRepository.getSyncStatus().snapshotDate)
        Assertions.assertEquals(5, stockRepository.findAll().sumOf { it.quantityOnHand })
        Assertions.assertTrue(inboundSupplyRepository.findAll().all { it.received })
    }

    @Test
    fun `shared recurring schedule can be updated removed and executed through JobRunr`() {
        val registration = StockLedgerSyncJob(
            stockLedgerProcessor, StockLedgerSyncProperties(cron = "0 0 31 12 *"), jobScheduler
        )
        try {
            registration.register()
            registration.register()
            Assertions.assertEquals(1, jobStorage.recurringJobs.count { it.id == StockLedgerSyncJob.RECURRING_ID })
            ledgerSyncJob.register()
            Assertions.assertEquals(1, jobStorage.recurringJobs.count { it.id == StockLedgerSyncJob.RECURRING_ID })

            val jobId = jobScheduler.enqueue { ledgerSyncJob.execute() }
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted {
                Assertions.assertEquals(StateName.SUCCEEDED, jobStorage.getJobById(jobId).state)
            }
            Assertions.assertEquals(LocalDate.now(clock), stockLedgerSyncStatusRepository.getSyncStatus().snapshotDate)
            Assertions.assertNotNull(stockLedgerSyncStatusRepository.getSyncStatus().lastSuccessAt)
        } finally {
            jobScheduler.deleteRecurringJob(StockLedgerSyncJob.RECURRING_ID)
        }
    }

    @Test
    fun `backorder orchestration refreshes holds without synchronizing stock`() {
        val today = LocalDate.now()
        allocationService.allocation(reservationRequest("BACKORDER-PROJECTION", 5))
        stockRepository.save(
            StockEntity(sku = "MAKI-ZALM", lotId = "BACKORDER-PROJECTION-LOT", receivedDate = today, quantityOnHand = 3)
        )
        val run = processingService.process(AllocationRequest(
            requestId = UUID.randomUUID(), asOfDate = today, runType = AllocationRunType.BACKORDER_FULFILLMENT
        ))
        Assertions.assertEquals(3, run.allocations.single().allocatedFromInventory)
        Assertions.assertEquals(3, snapshotRepository.findLotsByDate(today).single().quantityReserved)
        Assertions.assertEquals(3, stockRepository.findAll().single().quantityOnHand)
        Assertions.assertNull(stockLedgerSyncStatusRepository.getSyncStatus().lastSuccessAt)
    }

    @Test
    fun `manual sync with omitted date uses the business clock instead of the JVM date`() {
        Mockito.`when`(clock.zone).thenReturn(ZoneId.of("Pacific/Kiritimati"))
        setNow(Instant.parse("2026-10-20T12:30:00Z"))
        val result = stockLedgerProcessor.process(ProcessDailyStockRequest())
        Assertions.assertEquals(LocalDate.of(2026, 10, 21), result.snapshotDate)
        Assertions.assertEquals(result.snapshotDate, stockLedgerSyncStatusRepository.getSyncStatus().snapshotDate)
    }

    @Test
    fun `overlapping scheduled and manual sync receive each shipment only once`() {
        val today = LocalDate.now()
        allocationService.allocation(AllocationRequest(
            requestId = UUID.randomUUID(), asOfDate = today, runType = AllocationRunType.NEW_DEMAND,
            inboundShipments = listOf(singleLineShipment("CONCURRENT-SYNC", "MAKI-ZALM", 5, today))
        ))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val scheduled = executor.submit { ledgerSyncJob.execute() }
            val manual = executor.submit { stockLedgerProcessor.process(ProcessDailyStockRequest(today)) }
            scheduled.get(20, TimeUnit.SECONDS)
            manual.get(20, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        Assertions.assertEquals(5, stockRepository.findAll().single().quantityOnHand)
        Assertions.assertTrue(inboundSupplyRepository.findAll().single().received)
        Assertions.assertNotNull(stockLedgerSyncStatusRepository.getSyncStatus().lastSuccessAt)
    }

    @Test
    fun `rolled back ledger sync preserves previous snapshots receipt state and success timestamp`() {
        val today = LocalDate.now()
        processingService.process(AllocationRequest(
            requestId = UUID.randomUUID(), asOfDate = today, runType = AllocationRunType.NEW_DEMAND,
            inboundShipments = listOf(singleLineShipment("ROLLBACK-SYNC", "MAKI-ZALM", 5, today))
        ))
        val snapshots = snapshotRepository.findDailyByDate(today)
        val owner = UUID.randomUUID()
        val run = ledgerRunRepository.begin(today, owner, clock.instant())
        Assertions.assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                stockLedgerSkuWorker.process(run, owner, "MAKI-ZALM")
                throw IllegalStateException("Roll back enclosing transaction")
            }
        }
        Assertions.assertEquals(snapshots, snapshotRepository.findDailyByDate(today))
        Assertions.assertFalse(inboundSupplyRepository.findAll().single().received)
        Assertions.assertTrue(stockRepository.findAll().isEmpty())
        Assertions.assertNull(stockLedgerSyncStatusRepository.getSyncStatus().lastSuccessAt)
        Assertions.assertEquals(0, ledgerRunRepository.getProgress()!!.completedSkus)
        Assertions.assertTrue(ledgerRunRepository.pendingReceipts(100).isEmpty())
    }

    @Test
    fun `allocates distinct SKUs independently and aggregates the run idempotently`() {
        val today = LocalDate.now()
        stockRepository.save(
            StockEntity(
                sku = "MAKI-ZALM",
                lotId = "LOT-A",
                receivedDate = today.minusDays(1),
                quantityOnHand = 2
            )
        )
        val request = AllocationRequest(
            requestId = UUID.fromString("aaaaaaaa-1111-1111-1111-aaaaaaaaaaaa"),
            asOfDate = today,
            runType = AllocationRunType.NEW_DEMAND,
            demandOrders = listOf(
                singleLineOrder(
                    orderId = "ORDER-SKU-B",
                    customer = "Inbound Customer",
                    sku = "MAKI-TONIJN",
                    requestedQuantity = 2,
                    priority = 2,
                    placedDate = today,
                    requiredDeliveryDate = today.plusDays(1)
                ),
                singleLineOrder(
                    orderId = "ORDER-SKU-A",
                    customer = "Inventory Customer",
                    sku = "MAKI-ZALM",
                    requestedQuantity = 2,
                    priority = 1,
                    placedDate = today,
                    requiredDeliveryDate = today
                )
            ),
            inboundShipments = listOf(
                singleLineShipment(
                    shipmentId = "SHIP-SKU-B",
                    sku = "MAKI-TONIJN",
                    expectedQuantity = 2,
                    expectedArrivalDate = today.plusDays(1)
                )
            )
        )

        val hash = requestHash(request)
        val pendingRun = allocationRunLifecycleService.beginOrResume(request, hash)
        documentService.initialize(pendingRun.id, request)
        val inventorySkuOrders = request.allocationLines().filter { it.sku == "MAKI-ZALM" }
        val firstSkuResult = skuAllocationProcessor.process(
            runId = pendingRun.id,
            requestHash = hash,
            sku = "MAKI-ZALM",
            asOfDate = today,
            orders = inventorySkuOrders
        )
        val retriedSkuResult = skuAllocationProcessor.process(
            runId = pendingRun.id,
            requestHash = hash,
            sku = "MAKI-ZALM",
            asOfDate = today,
            orders = inventorySkuOrders
        )
        Assertions.assertEquals(firstSkuResult, retriedSkuResult)

        val firstRun = allocationService.allocation(request)
        val retryRun = allocationService.allocation(request)

        Assertions.assertEquals(firstRun.runId, retryRun.runId)
        Assertions.assertEquals(listOf("ORDER-SKU-A", "ORDER-SKU-B"), firstRun.allocations.map { it.orderId })
        Assertions.assertEquals(2, firstRun.allocations[0].allocatedFromInventory)
        Assertions.assertEquals(2, firstRun.allocations[1].allocatedFromInbound)
        Assertions.assertEquals(2, stockRepository.findAll().single().quantityReserved)
        Assertions.assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT quantity_reserved FROM inbound_supply WHERE shipment_id = 'SHIP-SKU-B'",
            Int::class.java
        )!!)
        Assertions.assertEquals(2, recordRepository.count())
    }

    @Test
    fun `allocates by priority and FIFO then keeps and fulfills backorders`() {
        stockRepository.saveAll(
            listOf(
                StockEntity(
                    sku = "MAKI-ZALM",
                    lotId = "LOT-OLD",
                    warehouseCode = "A-01",
                    receivedDate = LocalDate.parse("2026-10-01"),
                    quantityOnHand = 2
                ),
                StockEntity(
                    sku = "MAKI-ZALM",
                    lotId = "LOT-NEW",
                    warehouseCode = "A-02",
                    receivedDate = LocalDate.parse("2026-10-05"),
                    quantityOnHand = 1
                )
            )
        )
        val allocationRequest = AllocationRequest(
            requestId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            asOfDate = LocalDate.parse("2026-10-08"),
            demandOrders = listOf(
                singleLineOrder(
                    orderId = "ORDER-HIGH",
                    customer = "Priority Customer",
                    sku = "MAKI-ZALM",
                    requestedQuantity = 5,
                    priority = 1,
                    placedDate = LocalDate.parse("2026-10-01"),
                    requiredDeliveryDate = LocalDate.parse("2026-10-20")
                ),
                singleLineOrder(
                    orderId = "ORDER-FIFO-1",
                    customer = "First Customer",
                    sku = "MAKI-ZALM",
                    requestedQuantity = 5,
                    priority = 2,
                    placedDate = LocalDate.parse("2026-10-02"),
                    requiredDeliveryDate = LocalDate.parse("2026-10-15")
                ),
                singleLineOrder(
                    orderId = "ORDER-FIFO-2",
                    customer = "Second Customer",
                    sku = "MAKI-ZALM",
                    requestedQuantity = 5,
                    priority = 2,
                    placedDate = LocalDate.parse("2026-10-03"),
                    requiredDeliveryDate = LocalDate.parse("2026-10-15")
                )
            ),
            inboundShipments = listOf(
                singleLineShipment(
                    shipmentId = "SHIP-ON-TIME",
                    sku = "MAKI-ZALM",
                    expectedQuantity = 4,
                    expectedArrivalDate = LocalDate.parse("2026-10-14")
                ),
                singleLineShipment(
                    shipmentId = "SHIP-TOO-LATE",
                    sku = "MAKI-ZALM",
                    expectedQuantity = 100,
                    expectedArrivalDate = LocalDate.parse("2026-10-18")
                )
            ),
            runType = AllocationRunType.NEW_DEMAND
        )
        val initialRun = allocationService.allocation(allocationRequest)
        val retryRun = allocationService.allocation(allocationRequest)

        Assertions.assertEquals(initialRun.runId, retryRun.runId)
        Assertions.assertEquals(2, retryRun.openBackorderCount)
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            allocationService.allocation(
                allocationRequest.copy(
                    demandOrders = allocationRequest.demandOrders.map {
                        if (it.orderId == "ORDER-HIGH") it.copy(customer = "Changed Customer") else it
                    }
                )
            )
        }
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            allocationService.allocation(
                allocationRequest.copy(
                    requestId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    demandOrders = listOf(allocationRequest.demandOrders.first())
                )
            )
        }
        Assertions.assertEquals(AllocationRunType.NEW_DEMAND, initialRun.runType)
        Assertions.assertEquals(
            listOf("ORDER-HIGH", "ORDER-FIFO-1", "ORDER-FIFO-2"),
            initialRun.allocations.map { it.orderId }
        )
        Assertions.assertEquals(3, initialRun.allocations[0].allocatedFromInventory)
        Assertions.assertEquals(2, initialRun.allocations[0].allocatedFromInbound)
        Assertions.assertEquals("LOT-OLD", initialRun.allocations[0].sources.first().sourceId)
        Assertions.assertEquals(AllocationStatus.PARTIALLY_BACKORDERED, initialRun.allocations[1].status)
        Assertions.assertEquals(3, initialRun.allocations[1].backorderedQuantity)
        Assertions.assertEquals(AllocationStatus.BACKORDERED, initialRun.allocations[2].status)
        Assertions.assertEquals(5, initialRun.allocations[2].backorderedQuantity)
        Assertions.assertEquals(2, initialRun.openBackorderCount)
        Assertions.assertEquals(2, backorderRepository.count())
        Assertions.assertEquals(3, stockRepository.findAll().sumOf { it.quantityReserved })
        Assertions.assertEquals(3, stockReservationRepository.findAll().sumOf { it.quantity })

        stockRepository.save(
            StockEntity(
                sku = "MAKI-ZALM",
                lotId = "LOT-RESTOCK",
                warehouseCode = "B-01",
                receivedDate = LocalDate.parse("2026-10-18"),
                quantityOnHand = 6
            )
        )
        val fulfillmentRequest = AllocationRequest(
            requestId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            asOfDate = LocalDate.parse("2026-10-18"),
            inboundShipments = listOf(
                singleLineShipment(
                    shipmentId = "SHIP-ARRIVED",
                    sku = "MAKI-ZALM",
                    expectedQuantity = 1,
                    expectedArrivalDate = LocalDate.parse("2026-10-14")
                ),
                singleLineShipment(
                    shipmentId = "SHIP-NOT-ARRIVED",
                    sku = "MAKI-ZALM",
                    expectedQuantity = 20,
                    expectedArrivalDate = LocalDate.parse("2026-10-20")
                )
            ),
            runType = AllocationRunType.BACKORDER_FULFILLMENT
        )
        val fulfillmentRun = allocationService.allocateOpenBackorders(fulfillmentRequest)
        val fulfillmentRetry = allocationService.allocateOpenBackorders(fulfillmentRequest)

        Assertions.assertEquals(fulfillmentRun.runId, fulfillmentRetry.runId)
        Assertions.assertEquals(AllocationRunType.BACKORDER_FULFILLMENT, fulfillmentRun.runType)
        Assertions.assertEquals(1, fulfillmentRun.openBackorderCount)
        Assertions.assertEquals(2, allocationRunLifecycleService.getRun(initialRun.runId).openBackorderCount)
        Assertions.assertEquals("ORDER-FIFO-1", fulfillmentRun.allocations[0].orderId)
        Assertions.assertEquals(AllocationStatus.FULFILLED, fulfillmentRun.allocations[0].status)
        Assertions.assertEquals("ORDER-FIFO-2", fulfillmentRun.allocations[1].orderId)
        Assertions.assertEquals(AllocationStatus.PARTIALLY_FULFILLED, fulfillmentRun.allocations[1].status)
        Assertions.assertEquals(1, fulfillmentRun.allocations[1].allocatedFromInbound)
        Assertions.assertEquals(1, fulfillmentRun.allocations[1].backorderedQuantity)
        Assertions.assertEquals(1, fulfillmentRun.openBackorderCount)

        val report = reportGenerator.process(
            allocationRunLifecycleService.getRun(initialRun.runId),
            temporaryDirectory.resolve("allocation.xlsx")
        )
        Assertions.assertTrue(Files.exists(report))
        XSSFWorkbook(Files.newInputStream(report)).use { workbook ->
            Assertions.assertEquals(4, workbook.getSheet("Allocation Details").physicalNumberOfRows)
            Assertions.assertEquals(5, workbook.getSheet("Source Allocations").physicalNumberOfRows)
            Assertions.assertEquals(2, workbook.getSheet("Pivot Summary").pivotTables.size)
            val formulaSummary = workbook.getSheet("Formula Summary")
            Assertions.assertEquals(4, formulaSummary.physicalNumberOfRows)
            Assertions.assertEquals(
                "SUMIFS('Allocation Details'!I:I,'Allocation Details'!E:E,A2,'Allocation Details'!M:M,B2,'Allocation Details'!N:N,C2)",
                formulaSummary.getRow(1).getCell(3).cellFormula
            )
            Assertions.assertEquals(
                "SUMIFS('Allocation Details'!L:L,'Allocation Details'!E:E,A2,'Allocation Details'!M:M,B2,'Allocation Details'!N:N,C2)",
                formulaSummary.getRow(1).getCell(6).cellFormula
            )
        }
    }

    @Test
    fun `allocation rebuilds daily projections with physical allocation and backorder values`() {
        val today = LocalDate.now()
        stockRepository.save(
            StockEntity(
                sku = "MAKI-ZALM",
                lotId = "PROJECTION-LOT",
                warehouseCode = "MAIN",
                receivedDate = today.minusDays(1),
                quantityOnHand = 10
            )
        )
        val result = allocationService.allocation(
            AllocationRequest(
                requestId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                asOfDate = today,
                demandOrders = listOf(
                    singleLineOrder(
                        orderId = "PROJECTION-ORDER-1",
                        customer = "First",
                        sku = "MAKI-ZALM",
                        requestedQuantity = 4,
                        priority = 1,
                        placedDate = today,
                        requiredDeliveryDate = today.plusDays(1)
                    ),
                    singleLineOrder(
                        orderId = "PROJECTION-ORDER-2",
                        customer = "Second",
                        sku = "MAKI-ZALM",
                        requestedQuantity = 9,
                        priority = 2,
                        placedDate = today,
                        requiredDeliveryDate = today.plusDays(2)
                    )
                ),
                runType = AllocationRunType.NEW_DEMAND
            )
        )
        markConfirmed("PROJECTION-ORDER-1", "PROJECTION-ORDER-2")
        stockProjectionProcessor.process(today)

        Assertions.assertEquals(3, result.allocations.last().backorderedQuantity)
        Assertions.assertEquals(
            listOf(10, 6, 0),
            snapshotRepository
                .findDailyBySkuAndWarehouse("MAKI-ZALM", "MAIN")
                .map { it.closingStock }
        )
        Assertions.assertEquals(
            listOf(0, 4, 6),
            snapshotRepository
                .findDailyBySkuAndWarehouse("MAKI-ZALM", "MAIN")
                .map { it.allocatedQuantity }
        )
        Assertions.assertEquals(
            3,
            snapshotRepository.findDailyByDate(today.plusDays(2)).single().shortageQuantity
        )
        Assertions.assertEquals(10, stockRepository.findAll().single().quantityOnHand)
    }

    @Test
    fun `repeated projection rebuilds read fresh snapshots without detaching managed stock`() {
        val today = LocalDate.now()
        val stock = stockRepository.save(
            StockEntity(sku = "MAKI-ZALM", lotId = "JDBC-REBUILD", receivedDate = today, quantityOnHand = 3)
        )
        TransactionTemplate(transactionManager).executeWithoutResult {
            val managedStock = stockRepository.findById(stock.id).orElseThrow()
            stockProjectionProcessor.process(today)
            Assertions.assertEquals(3, snapshotRepository.findDailyByDate(today).single().closingStock)
            Assertions.assertEquals(3, snapshotRepository.findLotsByDate(today).single().closingStock)

            managedStock.quantityOnHand = 7
            stockProjectionProcessor.process(today)
            Assertions.assertEquals(7, snapshotRepository.findDailyByDate(today).single().closingStock)
            Assertions.assertEquals(7, snapshotRepository.findLotsByDate(today).single().closingStock)
            Assertions.assertTrue(entityManager.contains(managedStock))
            managedStock.quantityOnHand = 9
        }
        Assertions.assertEquals(9, stockRepository.findById(stock.id).orElseThrow().quantityOnHand)
    }

    @Test
    fun `empty projection removes current and future JDBC snapshots but preserves history`() {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)
        stockRepository.save(
            StockEntity(sku = "MAKI-ZALM", lotId = "JDBC-HISTORY", receivedDate = yesterday, quantityOnHand = 3)
        )
        stockProjectionProcessor.process(today)
        val dailySnapshot = snapshotRepository.findDailyByDate(today).single()
        val lotSnapshot = snapshotRepository.findLotsByDate(today).single()
        TransactionTemplate(transactionManager).executeWithoutResult {
            snapshotRepository.replaceSkuFrom(
                "MAKI-ZALM",
                yesterday,
                listOf(
                    dailySnapshot.copy(id = UUID.randomUUID(), snapshotDate = yesterday),
                    dailySnapshot,
                    dailySnapshot.copy(id = UUID.randomUUID(), snapshotDate = tomorrow)
                ),
                listOf(
                    lotSnapshot.copy(snapshotDate = yesterday),
                    lotSnapshot,
                    lotSnapshot.copy(snapshotDate = tomorrow)
                )
            )
        }
        val historicalDaily = snapshotRepository.findDailyByDate(yesterday)
        val historicalLots = snapshotRepository.findLotsByDate(yesterday)
        stockRepository.deleteAll()

        stockProjectionProcessor.process(today)

        Assertions.assertEquals(historicalDaily, snapshotRepository.findDailyByDate(yesterday))
        Assertions.assertEquals(historicalLots, snapshotRepository.findLotsByDate(yesterday))
        listOf(today, tomorrow).forEach { date ->
            Assertions.assertTrue(snapshotRepository.findDailyByDate(date).isEmpty())
            Assertions.assertTrue(snapshotRepository.findLotsByDate(date).isEmpty())
        }
    }

    @Test
    fun `failed JDBC snapshot insertion rolls back both replacement and JPA stock changes`() {
        val today = LocalDate.now()
        val stock = stockRepository.save(
            StockEntity(sku = "MAKI-ZALM", lotId = "JDBC-ROLLBACK", receivedDate = today, quantityOnHand = 3)
        )
        stockProjectionProcessor.process(today)
        val dailySnapshots = snapshotRepository.findDailyByDate(today)
        val lotSnapshots = snapshotRepository.findLotsByDate(today)

        Assertions.assertThrows(DataIntegrityViolationException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                stockRepository.findById(stock.id).orElseThrow().quantityOnHand = 8
                stockRepository.flush()
                snapshotRepository.replaceSkuFrom(
                    "MAKI-ZALM",
                    today,
                    dailySnapshots.map { it.copy(closingStock = 8) },
                    lotSnapshots.map { it.copy(closingStock = 8, quantityReserved = 9) }
                )
            }
        }

        Assertions.assertEquals(dailySnapshots, snapshotRepository.findDailyByDate(today))
        Assertions.assertEquals(lotSnapshots, snapshotRepository.findLotsByDate(today))
        Assertions.assertEquals(3, stockRepository.findById(stock.id).orElseThrow().quantityOnHand)
    }

    @Test
    fun `daily stock sync applies today's snapshot and preserves future reservations`() {
        val today = LocalDate.now()
        stockRepository.save(
            StockEntity(
                sku = "MAKI-ZALM",
                lotId = "SYNC-LOT",
                warehouseCode = "MAIN",
                receivedDate = today.minusDays(1),
                quantityOnHand = 6
            )
        )
        allocationService.allocation(
            AllocationRequest(
                requestId = UUID.fromString("55555555-5555-5555-5555-555555555555"),
                asOfDate = today,
                demandOrders = listOf(
                    singleLineOrder(
                        orderId = "DUE-ORDER",
                        customer = "Due",
                        sku = "MAKI-ZALM",
                        requestedQuantity = 3,
                        priority = 1,
                        placedDate = today,
                        requiredDeliveryDate = today
                    ),
                    singleLineOrder(
                        orderId = "FUTURE-ORDER",
                        customer = "Future",
                        sku = "MAKI-ZALM",
                        requestedQuantity = 2,
                        priority = 2,
                        placedDate = today,
                        requiredDeliveryDate = today.plusDays(1)
                    )
                ),
                runType = AllocationRunType.NEW_DEMAND
            )
        )

        markConfirmed("DUE-ORDER", "FUTURE-ORDER")
        stockProjectionProcessor.process(today)
        val syncRequest = ProcessDailyStockRequest(today)
        stockLedgerProcessor.process(syncRequest)
        stockLedgerProcessor.process(syncRequest)

        val stock = stockRepository.findAll().single()
        Assertions.assertEquals(3, stock.quantityOnHand)
        Assertions.assertEquals(2, stock.quantityReserved)
        Assertions.assertEquals(listOf("FUTURE-ORDER"), stockReservationRepository.findAll().map { it.orderId })
    }

    @Test
    fun `daily stock sync receives arrived inbound lots and converts future reservations`() {
        val today = LocalDate.now()
        val orderRequest = AllocationRequest(
            requestId = UUID.fromString("66666666-6666-6666-6666-666666666666"),
            asOfDate = today,
            demandOrders = listOf(
                singleLineOrder(
                    orderId = "INBOUND-ORDER",
                    customer = "Inbound",
                    sku = "MAKI-ZALM",
                    requestedQuantity = 3,
                    priority = 1,
                    placedDate = today,
                    requiredDeliveryDate = today.plusDays(1)
                )
            ),
            inboundShipments = listOf(
                singleLineShipment(
                    shipmentId = "ARRIVED-SHIPMENT",
                    sku = "MAKI-ZALM",
                    expectedQuantity = 5,
                    expectedArrivalDate = today
                )
            ),
            runType = AllocationRunType.NEW_DEMAND
        )
        allocationService.allocation(orderRequest)

        stockProjectionProcessor.process(today)
        stockLedgerProcessor.process(ProcessDailyStockRequest(today))
        stockLedgerProcessor.process(ProcessDailyStockRequest(today))

        val stock = stockRepository.findAll().single()
        Assertions.assertEquals(inboundSupplyRepository.findAll().single().lotId, stock.lotId)
        Assertions.assertEquals(today, stock.receivedDate)
        Assertions.assertEquals(5, stock.quantityOnHand)
        Assertions.assertEquals(3, stock.quantityReserved)
        Assertions.assertEquals("INBOUND-ORDER", stockReservationRepository.findAll().single().orderId)
        Assertions.assertEquals(
            0,
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inbound_reservation", Int::class.java)
                ?: error("The inbound reservation count query returned no result.")
        )
    }

    @Test
    fun `allocations use previously registered unreceived supply`() {
        val today = LocalDate.now()
        val shipment = singleLineShipment(
            shipmentId = "REUSABLE-SHIPMENT",
            sku = "MAKI-ZALM",
            expectedQuantity = 5,
            expectedArrivalDate = today.plusDays(1)
        )
        allocationService.allocation(
            AllocationRequest(
                requestId = UUID.fromString("77777777-7777-7777-7777-777777777777"),
                asOfDate = today,
                demandOrders = listOf(
                    singleLineOrder(
                        orderId = "REGISTER-SHIPMENT",
                        customer = "First",
                        sku = "MAKI-ZALM",
                        requestedQuantity = 1,
                        priority = 1,
                        placedDate = today,
                        requiredDeliveryDate = today
                    )
                ),
                inboundShipments = listOf(shipment),
                runType = AllocationRunType.NEW_DEMAND
            )
        )

        val allocation = allocationService.allocation(
            AllocationRequest(
                requestId = UUID.fromString("88888888-8888-8888-8888-888888888888"),
                asOfDate = today,
                demandOrders = listOf(
                    singleLineOrder(
                        orderId = "USE-REGISTERED-SHIPMENT",
                        customer = "Second",
                        sku = "MAKI-ZALM",
                        requestedQuantity = 4,
                        priority = 1,
                        placedDate = today,
                        requiredDeliveryDate = today.plusDays(1)
                    )
                ),
                runType = AllocationRunType.NEW_DEMAND
            )
        ).allocations.single()

        Assertions.assertEquals(4, allocation.allocatedFromInbound)
        Assertions.assertEquals(0, allocation.backorderedQuantity)
        stockProjectionProcessor.process(today)
        stockLedgerProcessor.process(ProcessDailyStockRequest(today))
        Assertions.assertEquals(0, stockRepository.count())
    }

    @Test
    fun `separate allocation requests see and reserve the same persisted stock`() {
        stockRepository.save(
            StockEntity(
                sku = "MAKI-ZALM",
                lotId = "SHARED-LOT",
                warehouseCode = "MAIN",
                receivedDate = LocalDate.now().minusDays(1),
                quantityOnHand = 5
            )
        )
        val today = LocalDate.now()
        val firstRequest = AllocationRequest(
            requestId = UUID.fromString("99999999-9999-9999-9999-999999999999"),
            asOfDate = today,
            demandOrders = listOf(
                singleLineOrder(
                    orderId = "FIRST-ORDER",
                    customer = "First",
                    sku = "MAKI-ZALM",
                    requestedQuantity = 4,
                    priority = 1,
                    placedDate = today,
                    requiredDeliveryDate = today.plusDays(1)
                )
            ),
            runType = AllocationRunType.NEW_DEMAND
        )
        val secondRequest = AllocationRequest(
            requestId = UUID.fromString("88888888-8888-8888-8888-888888888888"),
            asOfDate = today,
            demandOrders = listOf(
                singleLineOrder(
                    orderId = "SECOND-ORDER",
                    customer = "Second",
                    sku = "MAKI-ZALM",
                    requestedQuantity = 2,
                    priority = 1,
                    placedDate = today,
                    requiredDeliveryDate = today.plusDays(1)
                )
            ),
            runType = AllocationRunType.NEW_DEMAND
        )

        Assertions.assertEquals(
            4,
            allocationService.allocation(firstRequest).allocations.single().allocatedFromInventory
        )
        val secondAllocation = allocationService.allocation(secondRequest).allocations.single()

        Assertions.assertEquals(1, secondAllocation.allocatedFromInventory)
        Assertions.assertEquals(1, secondAllocation.backorderedQuantity)
        Assertions.assertEquals(5, stockRepository.findAll().single().quantityReserved)
    }

    @Test
    fun `expiry releases the whole order at its deadline without changing supply or history`() {
        val today = LocalDate.now()
        allocationService.allocation(
            reservationRequest("KEEP-INBOUND", 2).copy(
                inboundShipments = listOf(singleLineShipment("EXPIRY-SHIPMENT", "MAKI-ZALM", 6, today))
            )
        )
        stockRepository.save(
            StockEntity(
                sku = "MAKI-ZALM",
                lotId = "EXPIRY-LOT",
                receivedDate = today.minusDays(1),
                quantityOnHand = 6
            )
        )
        allocationService.allocation(reservationRequest("KEEP-STOCK", 2))
        markConfirmed("KEEP-INBOUND", "KEEP-STOCK")
        val request = reservationRequest("EXPIRE-MIXED", 10)
        val run = allocationService.allocation(request)
        val claim = claimRepository.findById("EXPIRE-MIXED").orElseThrow()
        val deadline = requireNotNull(claim.reservationExpiresAt)
        Assertions.assertEquals(Duration.ofMinutes(30), Duration.between(claim.reservationCreatedAt, deadline))
        Assertions.assertEquals(2, run.allocations.single().backorderedQuantity)
        Assertions.assertEquals(6, stockRepository.findAll().single().quantityReserved)
        Assertions.assertEquals(6, inboundSupplyRepository.findAll().single().quantityReserved)

        setNow(deadline.minusMillis(1))
        Assertions.assertEquals(0, expiryService.expireBatch())
        setNow(deadline)
        mockMvc.perform(MockMvcRequestBuilders.get("/api/reservations/orders/EXPIRE-MIXED/status"))
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("EXPIRED"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.cleanupPending").value(true))

        Assertions.assertEquals(1, expiryService.expireBatch())
        Assertions.assertEquals(0, expiryService.expireBatch())
        val stock = stockRepository.findAll().single()
        val supply = inboundSupplyRepository.findAll().single()
        Assertions.assertEquals(6, stock.quantityOnHand)
        Assertions.assertEquals(2, stock.quantityReserved)
        Assertions.assertEquals(6, supply.expectedQuantity)
        Assertions.assertEquals(2, supply.quantityReserved)
        Assertions.assertFalse(supply.received)
        Assertions.assertFalse(backorderRepository.existsById("EXPIRE-MIXED"))
        Assertions.assertEquals(listOf("KEEP-STOCK"), stockReservationRepository.findAll().map { it.orderId })
        Assertions.assertEquals(OrderReservationStatus.EXPIRED,
            claimRepository.findById("EXPIRE-MIXED").orElseThrow().orderReservationStatus)
        Assertions.assertEquals(run, allocationService.allocation(request))
        Assertions.assertEquals(deadline,
            claimRepository.findById("EXPIRE-MIXED").orElseThrow().reservationExpiresAt)
        Assertions.assertEquals(2, stockRepository.findAll().single().quantityReserved)
        mockMvc.perform(MockMvcRequestBuilders.get("/api/reservations/orders/EXPIRE-MIXED/status"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.cleanupPending").value(false))
            .andExpect(MockMvcResultMatchers.jsonPath("$.expiredAt").value(deadline.toString()))
        mockMvc.perform(MockMvcRequestBuilders.get("/api/reservations/orders/UNKNOWN/status"))
            .andExpect(MockMvcResultMatchers.status().isNotFound)
    }

    @Test
    fun `pending deliveries and backorders do not become physical movements`() {
        val today = LocalDate.now()
        stockRepository.save(
            StockEntity(sku = "MAKI-ZALM", lotId = "PENDING-LOT", quantityOnHand = 3, receivedDate = today)
        )
        allocationService.allocation(reservationRequest("PENDING-DELIVERY", 5))
        stockProjectionProcessor.process(today)
        val snapshot = snapshotRepository.findDailyByDate(today).single()
        Assertions.assertEquals(3, snapshot.closingStock)
        Assertions.assertEquals(0, snapshot.allocatedQuantity)
        Assertions.assertEquals(0, snapshot.demandQuantity)
        Assertions.assertEquals(0, snapshot.shortageQuantity)
        Assertions.assertEquals(3, snapshotRepository.findLotsByDate(today).single().quantityReserved)

        stockLedgerProcessor.process(ProcessDailyStockRequest(today))
        stockLedgerProcessor.process(ProcessDailyStockRequest(today))
        Assertions.assertEquals(3, stockRepository.findAll().single().quantityOnHand)
        Assertions.assertEquals(3, stockRepository.findAll().single().quantityReserved)
        Assertions.assertEquals(1, stockReservationRepository.count())
        Assertions.assertEquals(1, backorderRepository.count())
    }

    @Test
    fun `overdue pending inbound delivery is received as a hold then expires against stock`() {
        val today = LocalDate.now()
        val request = reservationRequest("PENDING-RECEIPT", 3).copy(
            inboundShipments = listOf(singleLineShipment("PENDING-SHIPMENT", "MAKI-ZALM", 5, today))
        )
        allocationService.allocation(request)
        val deadline = requireNotNull(claimRepository.findById("PENDING-RECEIPT").orElseThrow().reservationExpiresAt)
        stockProjectionProcessor.process(today)
        stockLedgerProcessor.process(ProcessDailyStockRequest(today))
        stockLedgerProcessor.process(ProcessDailyStockRequest(today))
        val stock = stockRepository.findAll().single()
        Assertions.assertEquals(5, stock.quantityOnHand)
        Assertions.assertEquals(3, stock.quantityReserved)
        Assertions.assertEquals(today, stockReservationRepository.findAll().single().requiredDeliveryDate)
        Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inbound_reservation", Int::class.java))
        Assertions.assertTrue(inboundSupplyRepository.findAll().single().received)
        Assertions.assertEquals(0, inboundSupplyRepository.findAll().single().quantityReserved)
        Assertions.assertEquals(deadline,
            claimRepository.findById("PENDING-RECEIPT").orElseThrow().reservationExpiresAt)

        setNow(deadline)
        Assertions.assertEquals(1, expiryService.expireBatch())
        Assertions.assertEquals(5, stockRepository.findAll().single().quantityOnHand)
        Assertions.assertEquals(0, stockRepository.findAll().single().quantityReserved)
        Assertions.assertEquals(0, stockReservationRepository.count())
    }

    @Test
    fun `backorder fulfillment keeps the original deadline and skips overdue orders`() {
        val today = LocalDate.now()
        allocationService.allocation(reservationRequest("PENDING-BACKORDER", 5))
        val deadline = requireNotNull(
            claimRepository.findById("PENDING-BACKORDER").orElseThrow().reservationExpiresAt
        )
        stockRepository.save(StockEntity(sku = "MAKI-ZALM", lotId = "RESTOCK-1", quantityOnHand = 2))
        setNow(deadline.minusSeconds(1))
        val fulfillment = allocationService.allocateOpenBackorders(
            AllocationRequest(
                requestId = UUID.randomUUID(),
                asOfDate = today,
                runType = AllocationRunType.BACKORDER_FULFILLMENT
            )
        )
        Assertions.assertEquals(2, fulfillment.allocations.single().allocatedFromInventory)
        Assertions.assertEquals(deadline,
            claimRepository.findById("PENDING-BACKORDER").orElseThrow().reservationExpiresAt)
        stockRepository.save(StockEntity(sku = "MAKI-ZALM", lotId = "RESTOCK-2", quantityOnHand = 3))
        setNow(deadline)
        val overdueFulfillment = allocationService.allocateOpenBackorders(
            AllocationRequest(
                requestId = UUID.randomUUID(),
                asOfDate = today,
                runType = AllocationRunType.BACKORDER_FULFILLMENT
            )
        )
        Assertions.assertTrue(overdueFulfillment.allocations.isEmpty())
        Assertions.assertEquals(2, stockRepository.findAll().sumOf { it.quantityReserved })
        Assertions.assertEquals(1, expiryService.expireBatch())
        Assertions.assertEquals(0, backorderRepository.count())
        Assertions.assertEquals(0, stockRepository.findAll().sumOf { it.quantityReserved })
    }

    @Test
    fun `expiry batches cover fully backordered orders and ledger refresh accepts the empty result`() {
        val today = LocalDate.now()
        (1..3).forEach { allocationService.allocation(reservationRequest("EMPTY-$it", 1)) }
        val deadline = requireNotNull(claimRepository.findById("EMPTY-1").orElseThrow().reservationExpiresAt)
        setNow(deadline)
        Assertions.assertEquals(2, expiryService.expireBatch())
        Assertions.assertEquals(1, backorderRepository.count())
        val sync = stockLedgerProcessor.process(ProcessDailyStockRequest(today))
        Assertions.assertEquals(0, sync.skuCount)
        Assertions.assertEquals(0, sync.lotCount)
        Assertions.assertEquals(0, backorderRepository.count())
        Assertions.assertTrue(claimRepository.findAll().all { it.orderReservationStatus == OrderReservationStatus.EXPIRED })
        Assertions.assertTrue(snapshotRepository.findDailyByDate(today).isEmpty())
    }

    @Test
    fun `background expiry job drains overdue batches through transactional services`() {
        (1..3).forEach { allocationService.allocation(reservationRequest("JOB-EXPIRY-$it", 1)) }
        setNow(requireNotNull(claimRepository.findById("JOB-EXPIRY-1").orElseThrow().reservationExpiresAt))
        val jobId = jobScheduler.enqueue { expiryJob.execute() }

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted {
            Assertions.assertEquals(StateName.SUCCEEDED, jobStorage.getJobById(jobId).state)
        }
        Assertions.assertEquals(0, backorderRepository.count())
        Assertions.assertEquals(3, claimRepository.count())
        Assertions.assertTrue(claimRepository.findAll().all { it.orderReservationStatus == OrderReservationStatus.EXPIRED })
    }

    @Test
    fun `expiry rolls back source changes and lifecycle when projection rebuild fails`() {
        val today = LocalDate.now()
        stockRepository.save(StockEntity(sku = "MAKI-ZALM", lotId = "CONFLICT-LOT", quantityOnHand = 4))
        allocationService.allocation(reservationRequest("ROLLBACK-ORDER", 5))
        jdbcTemplate.update("INSERT INTO inbound_shipment (shipment_id) VALUES ('CONFLICT-SHIPMENT')")
        inboundSupplyRepository.save(
            InboundSupplyEntity(
                shipmentId = "CONFLICT-SHIPMENT",
                shipmentLineId = "CONFLICT-LINE",
                lotId = "CONFLICT-LOT",
                sku = "MAKI-ZALM",
                expectedQuantity = 2,
                expectedArrivalDate = today
            )
        )
        setNow(requireNotNull(claimRepository.findById("ROLLBACK-ORDER").orElseThrow().reservationExpiresAt))

        Assertions.assertThrows(IllegalArgumentException::class.java) { expiryService.expireBatch() }
        Assertions.assertEquals(OrderReservationStatus.PENDING_CONFIRMATION,
            claimRepository.findById("ROLLBACK-ORDER").orElseThrow().orderReservationStatus)
        Assertions.assertEquals(4, stockRepository.findAll().single().quantityReserved)
        Assertions.assertEquals(1, stockReservationRepository.count())
        Assertions.assertEquals(1, backorderRepository.count())
        inboundSupplyRepository.deleteById("CONFLICT-LINE")

        jdbcTemplate.update("UPDATE stock SET quantity_reserved = 0")
        Assertions.assertThrows(IllegalStateException::class.java) { expiryService.expireBatch() }
        Assertions.assertEquals(OrderReservationStatus.PENDING_CONFIRMATION,
            claimRepository.findById("ROLLBACK-ORDER").orElseThrow().orderReservationStatus)
        Assertions.assertEquals(1, stockReservationRepository.count())
        jdbcTemplate.update("UPDATE stock SET quantity_reserved = 4")
        Assertions.assertEquals(1, expiryService.expireBatch())
    }

    @Test
    fun `ledger expires overdue holds instead of applying a stale reserved snapshot`() {
        val today = LocalDate.now()
        stockRepository.save(StockEntity(sku = "MAKI-ZALM", lotId = "STALE-LOT", quantityOnHand = 5))
        allocationService.allocation(reservationRequest("STALE-ORDER", 3))
        stockProjectionProcessor.process(today)
        Assertions.assertEquals(3, snapshotRepository.findLotsByDate(today).single().quantityReserved)
        setNow(requireNotNull(claimRepository.findById("STALE-ORDER").orElseThrow().reservationExpiresAt))

        val executor = Executors.newFixedThreadPool(2)
        try {
            val sync = executor.submit<Any> { stockLedgerProcessor.process(ProcessDailyStockRequest(today)) }
            val expiry = executor.submit<Int> { expiryService.expireBatch() }
            sync.get(20, TimeUnit.SECONDS)
            expiry.get(20, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        Assertions.assertEquals(5, stockRepository.findAll().single().quantityOnHand)
        Assertions.assertEquals(0, stockRepository.findAll().single().quantityReserved)
        Assertions.assertEquals(0, snapshotRepository.findLotsByDate(today).single().quantityReserved)
        Assertions.assertEquals(OrderReservationStatus.EXPIRED,
            claimRepository.findById("STALE-ORDER").orElseThrow().orderReservationStatus)
    }

    @Test
    fun `resuming a partially completed run does not recreate an expired SKU reservation`() {
        val today = LocalDate.now()
        stockRepository.save(StockEntity(sku = "MAKI-ZALM", lotId = "PARTIAL-LOT", quantityOnHand = 2))
        val firstOrder = reservationRequest("PARTIAL-FIRST", 2).demandOrders.single()
        val request = reservationRequest("PARTIAL-SECOND", 1).copy(
            demandOrders = listOf(
                firstOrder.copy(lines = firstOrder.lines + listOf(
                    DemandOrderLine("PARTIAL-SECOND", "MAKI-TONIJN", 1, today)
                ))
            )
        )
        val hash = requestHash(request)
        val run = allocationRunLifecycleService.beginOrResume(request, hash)
        documentService.initialize(run.id, request)
        skuAllocationProcessor.process(
            run.id, hash, "MAKI-ZALM", today, request.allocationLines().take(1)
        )
        val deadline = requireNotNull(claimRepository.findById("PARTIAL-FIRST").orElseThrow().reservationExpiresAt)
        setNow(deadline)
        Assertions.assertEquals(1, expiryService.expireBatch())

        val result = allocationService.allocation(request)
        Assertions.assertEquals(2, result.allocations.first().allocatedFromInventory)
        Assertions.assertEquals(0, stockRepository.findAll().single().quantityReserved)
        Assertions.assertEquals(OrderReservationStatus.EXPIRED,
            claimRepository.findById("PARTIAL-FIRST").orElseThrow().orderReservationStatus)
        Assertions.assertEquals(AllocationProcessingOutcome.SKIPPED_ORDER_EXPIRED,
            result.allocations.last().processingOutcome)
        Assertions.assertNull(result.allocations.last().status)
        Assertions.assertFalse(claimRepository.existsById("PARTIAL-SECOND"))
        Assertions.assertEquals(deadline,
            claimRepository.findById("PARTIAL-FIRST").orElseThrow().reservationExpiresAt)
    }

    @Test
    fun `lifecycle migration rejects orphan holds and backfills legacy commitments as confirmed`() {
        jdbcTemplate.execute("CREATE DATABASE reservation_upgrade_test")
        val legacyDataSource = DriverManagerDataSource(
            postgres.jdbcUrl.substringBeforeLast('/') + "/reservation_upgrade_test",
            postgres.username,
            postgres.password
        )
        Flyway.configure().dataSource(legacyDataSource).target("13").load().migrate()
        val legacy = JdbcTemplate(legacyDataSource)
        val runId = UUID.randomUUID()
        val stockId = UUID.randomUUID()
        legacy.update(
            "INSERT INTO allocation_run (id, request_id, request_hash, run_type, as_of_date, created_at) " +
                "VALUES (?, ?, ?, 'NEW_DEMAND', CURRENT_DATE, CURRENT_TIMESTAMP)",
            runId, UUID.randomUUID(), "legacy-hash"
        )
        legacy.update(
            "INSERT INTO allocation_order_claim (order_id, run_id, sku) VALUES ('LEGACY', ?, 'MAKI-ZALM')",
            runId
        )
        legacy.update(
            "INSERT INTO stock (id, sku, lot_id, received_date, quantity_on_hand, quantity_reserved) " +
                "VALUES (?, 'MAKI-ZALM', 'LEGACY-LOT', CURRENT_DATE, 5, 3)",
            stockId
        )
        legacy.update(
            "INSERT INTO stock_reservation (order_id, stock_id, quantity, required_delivery_date) " +
                "VALUES ('LEGACY', ?, 2, CURRENT_DATE), ('ORPHAN', ?, 1, CURRENT_DATE)",
            stockId, stockId
        )
        val migration = Flyway.configure().dataSource(legacyDataSource).target("14").load()
        val failure = Assertions.assertThrows(FlywayException::class.java) { migration.migrate() }
        Assertions.assertTrue(requireNotNull(failure.message).contains("no allocation order claim"))
        legacy.update("DELETE FROM stock_reservation WHERE order_id = 'ORPHAN'")
        legacy.update("UPDATE stock SET quantity_reserved = 2")
        migration.migrate()
        Assertions.assertEquals("CONFIRMED",
            legacy.queryForObject("SELECT reservation_status FROM allocation_order_claim", String::class.java))
        Assertions.assertNull(
            legacy.queryForObject("SELECT reservation_expires_at FROM allocation_order_claim", Instant::class.java))
        Assertions.assertEquals(2,
            legacy.queryForObject("SELECT quantity FROM stock_reservation", Int::class.java))

        val lineMigration = Flyway.configure().dataSource(legacyDataSource).load()
        val missingMetadata = Assertions.assertThrows(FlywayException::class.java) { lineMigration.migrate() }
        Assertions.assertTrue(requireNotNull(missingMetadata.message).contains("Missing or ambiguous metadata"))
        legacy.update(
            "INSERT INTO allocation_record (id, run_id, sequence_number, order_id, customer, sku, priority, " +
                "placed_date, required_delivery_date, requested_quantity, allocated_from_inventory, " +
                "allocated_from_inbound, backordered_quantity, status) " +
                "VALUES (?, ?, 0, 'LEGACY', 'Legacy customer', 'MAKI-ZALM', 1, CURRENT_DATE, CURRENT_DATE, " +
                "2, 2, 0, 0, 'ALLOCATED')",
            UUID.randomUUID(), runId
        )
        legacy.update(
            "INSERT INTO allocation_order_claim (order_id, run_id, sku, reservation_status, " +
                "reservation_created_at, reservation_expires_at) " +
                "VALUES ('LEGACY-PENDING', ?, 'MAKI-TONIJN', 'PENDING_CONFIRMATION', " +
                "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 minutes')",
            runId
        )
        legacy.update(
            "INSERT INTO open_backorder (order_id, customer, sku, priority, placed_date, required_delivery_date, " +
                "original_quantity, remaining_quantity, created_at, updated_at) " +
                "VALUES ('LEGACY-PENDING', 'Pending', 'MAKI-TONIJN', 1, CURRENT_DATE, CURRENT_DATE, 3, 3, " +
                "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
        )
        legacy.update("INSERT INTO inbound_supply (shipment_id, sku, expected_quantity, expected_arrival_date, received) " +
            "VALUES ('OLD-SHIP', 'MAKI-ZALM', 4, CURRENT_DATE, TRUE)")
        legacy.update("INSERT INTO stock (sku, lot_id, received_date, quantity_on_hand) " +
            "VALUES ('MAKI-ZALM', 'OLD-SHIP', CURRENT_DATE, 4)")
        val oldDeadline = legacy.queryForObject(
            "SELECT reservation_expires_at FROM allocation_order_claim WHERE order_id='LEGACY-PENDING'",
            java.sql.Timestamp::class.java
        )
        lineMigration.migrate()
        Assertions.assertEquals("CONFIRMED", legacy.queryForObject(
            "SELECT reservation_status FROM demand_order WHERE order_id='LEGACY'", String::class.java))
        Assertions.assertEquals("PENDING_CONFIRMATION", legacy.queryForObject(
            "SELECT reservation_status FROM demand_order WHERE order_id='LEGACY-PENDING'", String::class.java))
        Assertions.assertEquals(oldDeadline, legacy.queryForObject(
            "SELECT reservation_expires_at FROM demand_order WHERE order_id='LEGACY-PENDING'",
            java.sql.Timestamp::class.java))
        Assertions.assertEquals("LEGACY", legacy.queryForObject(
            "SELECT order_line_id FROM stock_reservation", String::class.java))
        Assertions.assertEquals("OLD-SHIP", legacy.queryForObject(
            "SELECT lot_id FROM inbound_supply", String::class.java))
        Assertions.assertEquals(4, legacy.queryForObject(
            "SELECT quantity_on_hand FROM stock WHERE lot_id='OLD-SHIP'", Int::class.java))
        Assertions.assertEquals("legacy-hash", legacy.queryForObject(
            "SELECT request_hash FROM allocation_run", String::class.java))
    }

    @Test
    fun `multiline order shares one lifecycle and expires all SKU and receipt reservations atomically`() {
        val today = LocalDate.now()
        stockRepository.save(StockEntity(
            sku = "MAKI-ZALM", lotId = "MULTI-STOCK", quantityOnHand = 5, receivedDate = today
        ))
        val request = AllocationRequest(
            UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND,
            demandOrders = listOf(DemandOrder("MULTI-ORDER", "Multiline", 1, today, listOf(
                DemandOrderLine("MULTI-Z1", "MAKI-ZALM", 2, today),
                DemandOrderLine("MULTI-Z2", "MAKI-ZALM", 6, today),
                DemandOrderLine("MULTI-T1", "MAKI-TONIJN", 3, today),
                DemandOrderLine("MULTI-T2", "MAKI-TONIJN", 4, today)
            ))),
            inboundShipments = listOf(InboundShipment("MULTI-SHIP", listOf(
                InboundShipmentLine("SUPPLY-Z", "MAKI-ZALM", 4, today),
                InboundShipmentLine("SUPPLY-T", "MAKI-TONIJN", 5, today),
                InboundShipmentLine("SUPPLY-FUTURE", "MAKI-ZALM", 7, today.plusDays(1), "SECONDARY")
            )))
        )
        val run = allocationService.allocation(request)
        Assertions.assertEquals(listOf("MULTI-Z1", "MULTI-Z2", "MULTI-T1", "MULTI-T2"),
            run.allocations.map { it.orderLineId })
        Assertions.assertEquals(1, claimRepository.count())
        Assertions.assertEquals(1, run.openBackorderCount)
        Assertions.assertEquals(2, run.allocations.last().backorderedQuantity)
        Assertions.assertEquals(2, stockReservationRepository.count())
        Assertions.assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inbound_reservation", Int::class.java))
        mockMvc.perform(MockMvcRequestBuilders.get("/api/reservations/orders/MULTI-ORDER/status"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.lines.length()").value(4))
            .andExpect(MockMvcResultMatchers.jsonPath("$.lines[1].orderLineId").value("MULTI-Z2"))
        stockLedgerProcessor.process(ProcessDailyStockRequest(today))
        stockLedgerProcessor.process(ProcessDailyStockRequest(today))
        Assertions.assertEquals(5, stockReservationRepository.count())
        Assertions.assertEquals(14, stockRepository.findAll().sumOf { it.quantityOnHand })
        Assertions.assertTrue(inboundSupplyRepository.findById("SUPPLY-Z").orElseThrow().received)
        Assertions.assertTrue(inboundSupplyRepository.findById("SUPPLY-T").orElseThrow().received)
        Assertions.assertFalse(inboundSupplyRepository.findById("SUPPLY-FUTURE").orElseThrow().received)
        Assertions.assertEquals(3, inboundSupplyRepository.findAll().map { it.lotId }.distinct().size)
        val source = run.allocations[1].sources.last()
        Assertions.assertEquals("MULTI-SHIP", source.shipmentId)
        Assertions.assertEquals("SUPPLY-Z", source.shipmentLineId)
        val deadline = requireNotNull(claimRepository.findById("MULTI-ORDER").orElseThrow().reservationExpiresAt)
        setNow(deadline)
        Assertions.assertEquals(1, expiryService.expireBatch())
        Assertions.assertEquals(0, stockReservationRepository.count())
        Assertions.assertEquals(0, backorderRepository.count())
        Assertions.assertEquals(0, stockRepository.findAll().sumOf { it.quantityReserved })
        Assertions.assertEquals(14, stockRepository.findAll().sumOf { it.quantityOnHand })
        Assertions.assertEquals(run, allocationService.allocation(request))
    }

    @Test
    fun `line identity cannot move between orders or mutate shipment membership and received quantities`() {
        val today = LocalDate.now()
        val order = DemandOrder("OWNER", "Owner", 1, today, listOf(
            DemandOrderLine("OWNED-LINE", "MAKI-ZALM", 2, today)
        ))
        val shipment = InboundShipment("OWNER-SHIP", listOf(
            InboundShipmentLine("OWNED-SUPPLY", "MAKI-ZALM", 5, today)
        ))
        val request = AllocationRequest(UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND,
            listOf(order), listOf(shipment))
        allocationService.allocation(request)
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            allocationService.allocation(request.copy(
                requestId = UUID.randomUUID(), demandOrders = listOf(order.copy(orderId = "OTHER"))
            ))
        }
        Assertions.assertFalse(claimRepository.existsById("OTHER"))
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            allocationService.allocation(request.copy(
                requestId = UUID.randomUUID(), demandOrders = emptyList(),
                inboundShipments = listOf(shipment.copy(lines = shipment.lines + InboundShipmentLine(
                    "EXTRA-SUPPLY", "MAKI-TONIJN", 1, today
                )))
            ))
        }
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            allocationService.allocation(request.copy(
                requestId = UUID.randomUUID(), demandOrders = listOf(order.copy(
                    orderId = "DUPLICATE", lines = order.lines + order.lines
                ))
            ))
        }
        stockLedgerProcessor.process(ProcessDailyStockRequest(today))
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            allocationService.allocation(request.copy(
                requestId = UUID.randomUUID(), demandOrders = emptyList(),
                inboundShipments = listOf(shipment.copy(lines = listOf(shipment.lines.single().copy(expectedQuantity = 6))))
            ))
        }
        val received = inboundSupplyRepository.findById("OWNED-SUPPLY").orElseThrow()
        Assertions.assertTrue(received.received)
        Assertions.assertEquals(5, received.expectedQuantity)
        Assertions.assertEquals(5, stockRepository.findAll().sumOf { it.quantityOnHand })
    }

    @Test
    fun `repeated SKU backorder lines retain separate reservations and count as one open order`() {
        val today = LocalDate.now()
        val request = AllocationRequest(
            UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND,
            demandOrders = listOf(DemandOrder("BACKORDER-HEADER", "Lines", 1, today, listOf(
                DemandOrderLine("BACKORDER-Z", "MAKI-ZALM", 2, today),
                DemandOrderLine("BACKORDER-A", "MAKI-ZALM", 2, today)
            )))
        )
        val run = allocationService.allocation(request)
        val deadline = claimRepository.findById("BACKORDER-HEADER").orElseThrow().reservationExpiresAt
        Assertions.assertEquals(1, run.openBackorderCount)
        Assertions.assertEquals(2, backorderRepository.count())
        jdbcTemplate.update("UPDATE open_backorder SET created_at = CURRENT_TIMESTAMP")
        stockRepository.save(StockEntity(sku = "MAKI-ZALM", lotId = "BACKORDER-STOCK", quantityOnHand = 3))
        val fulfillmentRequest = AllocationRequest(UUID.randomUUID(), today, AllocationRunType.BACKORDER_FULFILLMENT)
        val fulfillment = allocationService.allocateOpenBackorders(fulfillmentRequest)
        Assertions.assertEquals(listOf("BACKORDER-Z", "BACKORDER-A"), fulfillment.allocations.map { it.orderLineId })
        Assertions.assertEquals(listOf(2, 1), fulfillment.allocations.map { it.allocatedFromInventory })
        Assertions.assertEquals(2, stockReservationRepository.count())
        Assertions.assertEquals(1, fulfillment.openBackorderCount)
        Assertions.assertEquals("BACKORDER-A", backorderRepository.findAll().single().orderLineId)
        Assertions.assertEquals(deadline,
            claimRepository.findById("BACKORDER-HEADER").orElseThrow().reservationExpiresAt)
        Assertions.assertEquals(fulfillment, allocationService.allocateOpenBackorders(fulfillmentRequest))
    }

    @Test
    fun `concurrent requests cannot take ownership of the same order header and lines`() {
        stockRepository.save(StockEntity(sku = "MAKI-ZALM", lotId = "CONCURRENT-STOCK", quantityOnHand = 10))
        val request = reservationRequest("CONCURRENT-HEADER", 2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf(request, request.copy(requestId = UUID.randomUUID())).map { candidate ->
                executor.submit<Boolean> {
                    try {
                        allocationService.allocation(candidate)
                        true
                    } catch (exception: IllegalArgumentException) {
                        Assertions.assertTrue(requireNotNull(exception.message).contains("already been submitted"))
                        false
                    }
                }
            }
            Assertions.assertEquals(1, attempts.count { it.get(20, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
        Assertions.assertEquals(1, claimRepository.count())
        Assertions.assertEquals(1, stockReservationRepository.count())
        Assertions.assertEquals(2, stockRepository.findAll().single().quantityReserved)
    }

    @Test
    fun `independent registrations overlap without acquiring the retired projection gate`() {
        val today = LocalDate.now()
        val first = reservationRequest("INDEPENDENT-A", 2).copy(
            inboundShipments = listOf(singleLineShipment("INDEPENDENT-SHIP-A", "MAKI-ZALM", 5, today))
        )
        val second = AllocationRequest(
            UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND,
            demandOrders = listOf(singleLineOrder("INDEPENDENT-B", "Independent", "MAKI-TONIJN", 2, 1, today, today)),
            inboundShipments = listOf(singleLineShipment("INDEPENDENT-SHIP-B", "MAKI-TONIJN", 5, today))
        )
        val firstRun = allocationRunLifecycleService.beginOrResume(first, requestHash(first))
        val secondRun = allocationRunLifecycleService.beginOrResume(second, requestHash(second))
        val executor = Executors.newSingleThreadExecutor()
        try {
            TransactionTemplate(transactionManager).executeWithoutResult {
                documentService.initialize(firstRun.id, first)
                executor.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'")
                        documentService.initialize(secondRun.id, second)
                    }
                }.get(10, TimeUnit.SECONDS)
                executor.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        jdbcTemplate.execute("SET LOCAL lock_timeout = '200ms'")
                        stockProjectionLockRepository.acquire()
                    }
                }.get(10, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
        Assertions.assertEquals(2, claimRepository.count())
        Assertions.assertEquals(2, inboundSupplyRepository.count())
        Assertions.assertTrue(runRepository.findById(firstRun.id).orElseThrow().documentsInitialized)
        Assertions.assertTrue(runRepository.findById(secondRun.id).orElseThrow().documentsInitialized)
    }

    @ParameterizedTest
    @ValueSource(strings = ["order", "order-line", "shipment", "shipment-line"])
    fun `concurrent registrations cannot merge document identities`(identity: String) {
        val today = LocalDate.now()
        val isOrder = identity.startsWith("order")
        val first = AllocationRequest(
            UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND,
            demandOrders = if (isOrder) listOf(DemandOrder(
                "IDENTITY-OWNER", "Owner", 1, today,
                listOf(DemandOrderLine("IDENTITY-LINE", "MAKI-ZALM", 2, today))
            )) else emptyList(),
            inboundShipments = if (isOrder) emptyList() else listOf(InboundShipment(
                "IDENTITY-OWNER", listOf(InboundShipmentLine("IDENTITY-LINE", "MAKI-ZALM", 5, today))
            ))
        )
        val second = first.copy(
            requestId = UUID.randomUUID(),
            demandOrders = first.demandOrders.map { order ->
                order.copy(
                    orderId = if (identity == "order") order.orderId else "IDENTITY-OTHER",
                    lines = order.lines.map { line ->
                        line.copy(
                            orderLineId = if (identity == "order-line") line.orderLineId else "OTHER-LINE",
                            sku = "MAKI-TONIJN"
                        )
                    }
                )
            },
            inboundShipments = first.inboundShipments.map { shipment ->
                shipment.copy(
                    shipmentId = if (identity == "shipment") shipment.shipmentId else "IDENTITY-OTHER",
                    lines = shipment.lines.map { line ->
                        line.copy(
                            shipmentLineId = if (identity == "shipment-line") line.shipmentLineId else "OTHER-LINE",
                            sku = "MAKI-TONIJN"
                        )
                    }
                )
            }
        )
        val firstRun = allocationRunLifecycleService.beginOrResume(first, requestHash(first))
        val secondRun = allocationRunLifecycleService.beginOrResume(second, requestHash(second))
        val backend = ArrayBlockingQueue<Int>(1)
        val executor = Executors.newSingleThreadExecutor()
        lateinit var attempt: Future<IllegalArgumentException>
        try {
            TransactionTemplate(transactionManager).executeWithoutResult {
                documentService.initialize(firstRun.id, first)
                val holder = currentBackendPid()
                attempt = executor.submit<IllegalArgumentException> {
                    Assertions.assertThrows(IllegalArgumentException::class.java) {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'")
                            backend.put(currentBackendPid())
                            documentService.initialize(secondRun.id, second)
                        }
                    }
                }
                awaitBlockedBy(holder, requireNotNull(backend.poll(10, TimeUnit.SECONDS)))
            }
            attempt.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        Assertions.assertFalse(runRepository.findById(secondRun.id).orElseThrow().documentsInitialized)
        val table = if (isOrder) "demand_order_line" else "inbound_supply"
        val parent = if (isOrder) "order_id" else "shipment_id"
        Assertions.assertEquals(1, requireNotNull(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)))
        Assertions.assertEquals(
            "IDENTITY-OWNER:MAKI-ZALM",
            jdbcTemplate.queryForObject("SELECT $parent || ':' || sku FROM $table", String::class.java)
        )
        Assertions.assertEquals(
            if (isOrder) 1 else 0,
            claimRepository.count().toInt()
        )
        Assertions.assertEquals(0, stockReservationRepository.count())
    }

    @Test
    fun `inbound amendments wait for allocation and validate the committed reserved quantity`() {
        val today = LocalDate.now()
        val shipment = singleLineShipment("AMENDMENT-SHIP", "MAKI-ZALM", 100, today)
        allocationService.allocation(AllocationRequest(
            UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND, inboundShipments = listOf(shipment)
        ))
        val request = reservationRequest("AMENDMENT-ORDER", 50)
        val run = allocationRunLifecycleService.beginOrResume(request, requestHash(request))
        documentService.initialize(run.id, request)
        val amendment = AllocationRequest(
            UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND,
            inboundShipments = listOf(shipment.copy(lines = shipment.lines.map { it.copy(expectedQuantity = 20) }))
        )
        val amendmentRun = allocationRunLifecycleService.beginOrResume(amendment, requestHash(amendment))
        val backend = ArrayBlockingQueue<Int>(1)
        val executor = Executors.newSingleThreadExecutor()
        lateinit var attempt: Future<IllegalArgumentException>
        try {
            TransactionTemplate(transactionManager).executeWithoutResult {
                skuAllocationProcessor.process(
                    run.id, requestHash(request), "MAKI-ZALM", today, request.allocationLines()
                )
                val holder = currentBackendPid()
                attempt = executor.submit<IllegalArgumentException> {
                    Assertions.assertThrows(IllegalArgumentException::class.java) {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'")
                            backend.put(currentBackendPid())
                            documentService.initialize(amendmentRun.id, amendment)
                        }
                    }
                }
                awaitBlockedBy(holder, requireNotNull(backend.poll(10, TimeUnit.SECONDS)))
            }
            Assertions.assertTrue(requireNotNull(attempt.get(10, TimeUnit.SECONDS).message).contains("reservations"))
        } finally {
            executor.shutdownNow()
        }
        val supply = inboundSupplyRepository.findById("AMENDMENT-SHIP").orElseThrow()
        Assertions.assertEquals(100, supply.expectedQuantity)
        Assertions.assertEquals(50, supply.quantityReserved)
        Assertions.assertEquals(50, requireNotNull(jdbcTemplate.queryForObject(
            "SELECT SUM(quantity) FROM inbound_reservation WHERE shipment_line_id = 'AMENDMENT-SHIP'", Int::class.java
        )))
        Assertions.assertFalse(runRepository.findById(amendmentRun.id).orElseThrow().documentsInitialized)
    }

    @Test
    fun `registration retries do not hold the run lock while waiting for its SKU worker`() {
        val today = LocalDate.now()
        val request = reservationRequest("RETRY-LOCK-ORDER", 2).copy(
            inboundShipments = listOf(singleLineShipment("RETRY-LOCK-SHIP", "MAKI-ZALM", 5, today))
        )
        val run = allocationRunLifecycleService.beginOrResume(request, requestHash(request))
        documentService.initialize(run.id, request)
        val deadline = claimRepository.findById("RETRY-LOCK-ORDER").orElseThrow().reservationExpiresAt
        val backend = ArrayBlockingQueue<Int>(1)
        val executor = Executors.newSingleThreadExecutor()
        lateinit var attempt: Future<*>
        try {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'")
                stockProjectionLockRepository.acquireAllocationReadLock()
                jdbcTemplate.queryForObject(
                    "SELECT sku FROM allocation_sku_lock WHERE sku = 'MAKI-ZALM' FOR UPDATE", String::class.java
                )
                val holder = currentBackendPid()
                attempt = executor.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'")
                        backend.put(currentBackendPid())
                        documentService.initialize(run.id, request)
                    }
                }
                awaitBlockedBy(holder, requireNotNull(backend.poll(10, TimeUnit.SECONDS)))
                skuAllocationProcessor.process(
                    run.id, requestHash(request), "MAKI-ZALM", today, request.allocationLines()
                )
            }
            attempt.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        Assertions.assertEquals(2, inboundSupplyRepository.findById("RETRY-LOCK-SHIP").orElseThrow().quantityReserved)
        Assertions.assertEquals(deadline, claimRepository.findById("RETRY-LOCK-ORDER").orElseThrow().reservationExpiresAt)
    }

    @Test
    fun `registrations acquire overlapping inbound SKUs consistently despite reversed input order`() {
        val today = LocalDate.now()
        val skus = listOf("MAKI-KOMKOMMER", "MAKI-ZALM")
        skus.forEach { sku ->
            jdbcTemplate.update("INSERT INTO allocation_sku_lock (sku) VALUES (?) ON CONFLICT DO NOTHING", sku)
        }
        val requests = listOf(skus, skus.reversed()).mapIndexed { index, ordered ->
            AllocationRequest(
                UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND,
                inboundShipments = listOf(InboundShipment("LOCK-ORDER-SHIP-$index", ordered.map { sku ->
                    InboundShipmentLine("LOCK-ORDER-LINE-$index-$sku", sku, 5, today)
                }))
            )
        }
        val runs = requests.map { allocationRunLifecycleService.beginOrResume(it, requestHash(it)) }
        val executor = Executors.newFixedThreadPool(2)
        val attempts = mutableListOf<Future<*>>()
        try {
            TransactionTemplate(transactionManager).executeWithoutResult {
                stockProjectionLockRepository.acquireAllocationReadLock()
                jdbcTemplate.queryForObject(
                    "SELECT sku FROM allocation_sku_lock WHERE sku = ? FOR UPDATE", String::class.java, skus.first()
                )
                val holder = currentBackendPid()
                requests.zip(runs).forEach { (request, run) ->
                    val backend = ArrayBlockingQueue<Int>(1)
                    attempts += executor.submit {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'")
                            backend.put(currentBackendPid())
                            documentService.initialize(run.id, request)
                        }
                    }
                    awaitBlockedBy(holder, requireNotNull(backend.poll(10, TimeUnit.SECONDS)))
                }
            }
            attempts.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        Assertions.assertEquals(4, inboundSupplyRepository.count())
        runs.forEach { Assertions.assertTrue(runRepository.findById(it.id).orElseThrow().documentsInitialized) }
    }

    @Test
    fun `resuming initialized documents does not reapply shipment quantities or renew the order deadline`() {
        val today = LocalDate.now()
        val shipment = InboundShipment("INITIALIZED-SHIP", listOf(
            InboundShipmentLine("INITIALIZED-SUPPLY", "MAKI-ZALM", 5, today)
        ))
        val request = reservationRequest("INITIALIZED-ORDER", 2).copy(inboundShipments = listOf(shipment))
        val run = allocationRunLifecycleService.beginOrResume(request, requestHash(request))
        documentService.initialize(run.id, request)
        val deadline = claimRepository.findById("INITIALIZED-ORDER").orElseThrow().reservationExpiresAt
        allocationService.allocation(AllocationRequest(
            UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND,
            inboundShipments = listOf(shipment.copy(lines = listOf(shipment.lines.single().copy(expectedQuantity = 7))))
        ))
        allocationService.allocation(request)
        Assertions.assertEquals(7, inboundSupplyRepository.findById("INITIALIZED-SUPPLY").orElseThrow().expectedQuantity)
        Assertions.assertEquals(deadline,
            claimRepository.findById("INITIALIZED-ORDER").orElseThrow().reservationExpiresAt)
        Assertions.assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT SUM(quantity) FROM inbound_reservation WHERE order_id='INITIALIZED-ORDER'", Int::class.java))
    }

    @Test
    fun `legacy cached allocation JSON remains readable with stable migrated line identities`() {
        val record = objectMapper.readValue(
            """
                {
                  "orderId":"LEGACY-ORDER","customer":"Legacy","sku":"MAKI-ZALM","priority":1,
                  "placedDate":"2026-10-20","requiredDeliveryDate":"2026-10-21",
                  "requestedQuantity":2,"allocatedFromInventory":0,"allocatedFromInbound":2,
                  "backorderedQuantity":0,"status":"ALLOCATED",
                  "sources":[{"sourceType":"INBOUND","sourceId":"LEGACY-SHIP","quantity":2,
                    "sourceDate":"2026-10-20","warehouseLocation":"MAIN"}]
                }
            """.trimIndent(),
            AllocationRecord::class.java
        )
        Assertions.assertEquals("LEGACY-ORDER", record.orderLineId)
        Assertions.assertEquals(AllocationProcessingOutcome.PROCESSED, record.processingOutcome)
        Assertions.assertEquals("LEGACY-SHIP", record.sources.single().shipmentId)
        Assertions.assertEquals("LEGACY-SHIP", record.sources.single().shipmentLineId)
        Assertions.assertEquals(2, record.allocatedFromInbound)
    }

    @Test
    fun `projection waits for its SKU allocation but unrelated SKU projection can commit`() {
        val today = LocalDate.now(clock)
        stockRepository.saveAll(listOf(
            StockEntity(sku = "MAKI-ZALM", lotId = "SCOPED-Z", receivedDate = today, quantityOnHand = 10),
            StockEntity(sku = "MAKI-TONIJN", lotId = "SCOPED-T", receivedDate = today, quantityOnHand = 7)
        ))
        val request = reservationRequest("SCOPED-ORDER", 3)
        val run = allocationRunLifecycleService.beginOrResume(request, requestHash(request))
        documentService.initialize(run.id, request)
        val executor = Executors.newFixedThreadPool(2)
        val backend = ArrayBlockingQueue<Int>(1)
        lateinit var waiting: Future<*>
        try {
            TransactionTemplate(transactionManager).executeWithoutResult {
                skuAllocationProcessor.process(run.id, requestHash(request), "MAKI-ZALM", today, request.allocationLines())
                val holder = currentBackendPid()
                waiting = executor.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'")
                        backend.put(currentBackendPid())
                        stockProjectionProcessor.processSku(today, "MAKI-ZALM")
                    }
                }
                awaitBlockedBy(holder, requireNotNull(backend.poll(10, TimeUnit.SECONDS)))
                executor.submit {
                    stockProjectionProcessor.processSku(today, "MAKI-TONIJN")
                }.get(5, TimeUnit.SECONDS)
                Assertions.assertEquals(7, snapshotRepository.findDailyBySkuAndDate("MAKI-TONIJN", today).single().closingStock)
            }
            waiting.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        Assertions.assertEquals(3, snapshotRepository.findLotsBySkuAndDate("MAKI-ZALM", today).single().quantityReserved)
    }

    @Test
    fun `ledger commits an independent SKU while another allocation holds its SKU lock`() {
        val today = LocalDate.now(clock)
        stockRepository.saveAll(listOf(
            StockEntity(sku = "MAKI-ZALM", lotId = "LEDGER-Z", receivedDate = today, quantityOnHand = 10),
            StockEntity(sku = "MAKI-TONIJN", lotId = "LEDGER-T", receivedDate = today, quantityOnHand = 7)
        ))
        val request = reservationRequest("LEDGER-OVERLAP", 3)
        val run = allocationRunLifecycleService.beginOrResume(request, requestHash(request))
        documentService.initialize(run.id, request)
        val executor = Executors.newSingleThreadExecutor()
        lateinit var ledger: Future<*>
        try {
            TransactionTemplate(transactionManager).executeWithoutResult {
                skuAllocationProcessor.process(run.id, requestHash(request), "MAKI-ZALM", today, request.allocationLines())
                ledger = executor.submit { stockLedgerProcessor.processToday() }
                Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted {
                    Assertions.assertEquals(1, ledgerRunRepository.getProgress()?.completedSkus)
                }
                Assertions.assertFalse(ledger.isDone)
                Assertions.assertNull(stockLedgerSyncStatusRepository.getSyncStatus().lastSuccessAt)
                Assertions.assertEquals(7, snapshotRepository.findDailyBySkuAndDate("MAKI-TONIJN", today).single().closingStock)
            }
            ledger.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        Assertions.assertEquals("COMPLETED", ledgerRunRepository.getProgress()!!.status)
        Assertions.assertEquals(3, stockRepository.findAllBySku("MAKI-ZALM").single().quantityReserved)
    }

    @Test
    fun `scoped horizons and empty replacement never alter another SKU forecast`() {
        val today = LocalDate.now(clock)
        allocationService.allocation(AllocationRequest(
            UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND,
            inboundShipments = listOf(
                singleLineShipment("HORIZON-T", "MAKI-TONIJN", 5, today.plusDays(3)),
                singleLineShipment("HORIZON-Z", "MAKI-ZALM", 4, today)
            )
        ))
        stockProjectionProcessor.process(today)
        val other = snapshotRepository.findDailyBySkuAndWarehouse("MAKI-TONIJN", "MAIN")
        Assertions.assertEquals(4, other.size)
        Assertions.assertEquals(1, snapshotRepository.findDailyBySkuAndWarehouse("MAKI-ZALM", "MAIN").size)
        inboundSupplyRepository.deleteById("HORIZON-Z")

        stockProjectionProcessor.processSku(today, "MAKI-ZALM")

        Assertions.assertTrue(snapshotRepository.findDailyBySkuAndWarehouse("MAKI-ZALM", "MAIN").isEmpty())
        Assertions.assertTrue(snapshotRepository.findLotsBySkuAndDate("MAKI-ZALM", today).isEmpty())
        Assertions.assertEquals(other, snapshotRepository.findDailyBySkuAndWarehouse("MAKI-TONIJN", "MAIN"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `partial ledger work resumes or reconciles on a new date without duplicate receipts`(afterMidnight: Boolean) {
        Mockito.`when`(clock.zone).thenReturn(ZoneId.of("UTC"))
        setNow(Instant.parse("2026-10-20T10:00:00Z"))
        val today = LocalDate.now(clock)
        allocationService.allocation(AllocationRequest(
            UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND,
            inboundShipments = listOf(
                singleLineShipment("PARTIAL-T", "MAKI-TONIJN", 5, today),
                singleLineShipment("PARTIAL-Z", "MAKI-ZALM", 4, today)
            )
        ))
        val conflict = inboundSupplyRepository.findById("PARTIAL-Z").orElseThrow()
        stockRepository.save(
            StockEntity(sku = "MAKI-ZALM", lotId = conflict.lotId, receivedDate = today, quantityOnHand = 1)
        )

        Assertions.assertThrows(IllegalArgumentException::class.java) { stockLedgerProcessor.processToday() }
        val partial = ledgerRunRepository.getProgress()!!
        Assertions.assertEquals("PARTIAL", partial.status)
        Assertions.assertEquals(2, partial.totalSkus)
        Assertions.assertEquals(1, partial.completedSkus)
        Assertions.assertNull(stockLedgerSyncStatusRepository.getSyncStatus().lastSuccessAt)
        Assertions.assertTrue(inboundSupplyRepository.findById("PARTIAL-T").orElseThrow().received)
        Assertions.assertFalse(inboundSupplyRepository.findById("PARTIAL-Z").orElseThrow().received)
        Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_receipt_followup", Int::class.java))
        mockMvc.perform(MockMvcRequestBuilders.get("/api/stock/ledger/status"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.progress.status").value("PARTIAL"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.progress.completedSkus").value(1))

        stockRepository.deleteAll(stockRepository.findAllBySku("MAKI-ZALM"))
        if (afterMidnight) setNow(Instant.parse("2026-10-21T00:01:00Z"))
        val result = stockLedgerProcessor.processToday()
        val complete = ledgerRunRepository.getProgress()!!
        Assertions.assertEquals("COMPLETED", complete.status)
        Assertions.assertEquals(2, complete.completedSkus)
        Assertions.assertEquals(5, stockRepository.findAllBySku("MAKI-TONIJN").single().quantityOnHand)
        Assertions.assertEquals(4, stockRepository.findAllBySku("MAKI-ZALM").single().quantityOnHand)
        Assertions.assertEquals(LocalDate.now(clock), result.snapshotDate)
        if (afterMidnight) {
            Assertions.assertNotEquals(partial.runId, complete.runId)
            Assertions.assertEquals("SUPERSEDED", jdbcTemplate.queryForObject(
                "SELECT status FROM stock_ledger_run WHERE id = ?", String::class.java, partial.runId
            ))
        } else {
            Assertions.assertEquals(partial.runId, complete.runId)
        }
    }

    @Test
    fun `expired coordinator is fenced before applying work and committed work survives lost aggregation`() {
        val today = LocalDate.now(clock)
        allocationService.allocation(AllocationRequest(
            UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND,
            inboundShipments = listOf(singleLineShipment("FENCED-RECEIPT", "MAKI-ZALM", 5, today))
        ))
        val previousOwner = UUID.randomUUID()
        val first = ledgerRunRepository.begin(today, previousOwner, clock.instant())
        jdbcTemplate.update("UPDATE stock_ledger_coordinator SET lease_until = ?", java.sql.Timestamp.from(clock.instant().minusSeconds(1)))
        val owner = UUID.randomUUID()
        val resumed = ledgerRunRepository.begin(today, owner, clock.instant())
        Assertions.assertEquals(first.id, resumed.id)
        Assertions.assertThrows(RuntimeException::class.java) {
            stockLedgerSkuWorker.process(first, previousOwner, "MAKI-ZALM")
        }
        Assertions.assertTrue(stockRepository.findAll().isEmpty())
        stockLedgerSkuWorker.process(resumed, owner, "MAKI-ZALM")
        Assertions.assertEquals(1, ledgerRunRepository.getProgress()!!.completedSkus)
        Assertions.assertNull(stockLedgerSyncStatusRepository.getSyncStatus().lastSuccessAt)
        ledgerRunRepository.fail(resumed.id, owner, "Crash before parent aggregation", clock.instant())

        stockLedgerProcessor.processToday()

        Assertions.assertEquals(5, stockRepository.findAll().single().quantityOnHand)
        Assertions.assertEquals(resumed.id, ledgerRunRepository.getProgress()!!.runId)
        Assertions.assertEquals("COMPLETED", ledgerRunRepository.getProgress()!!.status)
    }

    @Test
    fun `expiry waits for the full sorted SKU set and releases every hold atomically`() {
        val today = LocalDate.now(clock)
        stockRepository.saveAll(listOf(
            StockEntity(sku = "MAKI-ZALM", lotId = "EXPIRY-Z", receivedDate = today, quantityOnHand = 5),
            StockEntity(sku = "MAKI-TONIJN", lotId = "EXPIRY-T", receivedDate = today, quantityOnHand = 5)
        ))
        allocationService.allocation(AllocationRequest(
            UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND,
            demandOrders = listOf(DemandOrder("EXPIRY-SORTED", "Expiry", 1, today, listOf(
                DemandOrderLine("EXPIRY-Z", "MAKI-ZALM", 2, today),
                DemandOrderLine("EXPIRY-T", "MAKI-TONIJN", 3, today)
            )))
        ))
        setNow(claimRepository.findById("EXPIRY-SORTED").orElseThrow().reservationExpiresAt!!)
        val executor = Executors.newSingleThreadExecutor()
        val backend = ArrayBlockingQueue<Int>(1)
        lateinit var expiry: Future<Int>
        try {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbcTemplate.queryForObject(
                    "SELECT sku FROM allocation_sku_lock WHERE sku = 'MAKI-ZALM' FOR UPDATE", String::class.java
                )
                val holder = currentBackendPid()
                expiry = executor.submit<Int> {
                    TransactionTemplate(transactionManager).execute {
                        jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'")
                        backend.put(currentBackendPid())
                        expiryService.expireBatch()
                    }!!
                }
                awaitBlockedBy(holder, requireNotNull(backend.poll(10, TimeUnit.SECONDS)))
                Assertions.assertEquals(OrderReservationStatus.PENDING_CONFIRMATION,
                    claimRepository.findById("EXPIRY-SORTED").orElseThrow().orderReservationStatus)
                Assertions.assertEquals(5, stockRepository.findAll().sumOf { it.quantityReserved })
            }
            Assertions.assertEquals(1, expiry.get(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
        Assertions.assertEquals(0, stockRepository.findAll().sumOf { it.quantityReserved })
        Assertions.assertEquals(0, stockReservationRepository.count())
        Assertions.assertEquals(OrderReservationStatus.EXPIRED,
            claimRepository.findById("EXPIRY-SORTED").orElseThrow().orderReservationStatus)
    }

    private fun currentBackendPid(): Int =
        requireNotNull(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Int::class.java))

    private fun awaitBlockedBy(holder: Int, waiter: Int) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until {
            // PostgreSQL may report a preceding waiter rather than the root lock holder.
            jdbcTemplate.queryForObject(
                """
                    WITH RECURSIVE blockers(pid) AS (
                        SELECT unnest(pg_blocking_pids(?))
                        UNION
                        SELECT unnest(pg_blocking_pids(pid)) FROM blockers
                    )
                    SELECT EXISTS (SELECT 1 FROM blockers WHERE pid = ?)
                """.trimIndent(),
                Boolean::class.java, waiter, holder
            ) == true
        }
    }

    private fun reservationRequest(orderId: String, quantity: Int) = AllocationRequest(
        requestId = UUID.randomUUID(),
        asOfDate = LocalDate.now(),
        runType = AllocationRunType.NEW_DEMAND,
        demandOrders = listOf(
            singleLineOrder(
                orderId = orderId,
                customer = "Reservation test",
                sku = "MAKI-ZALM",
                requestedQuantity = quantity,
                priority = 1,
                placedDate = LocalDate.now(),
                requiredDeliveryDate = LocalDate.now()
            )
        )
    )

    private fun singleLineOrder(
        orderId: String,
        customer: String,
        sku: String,
        requestedQuantity: Int,
        priority: Int,
        placedDate: LocalDate,
        requiredDeliveryDate: LocalDate
    ) = DemandOrder(
        orderId, customer, priority, placedDate,
        listOf(DemandOrderLine(orderId, sku, requestedQuantity, requiredDeliveryDate))
    )

    private fun singleLineShipment(
        shipmentId: String,
        sku: String,
        expectedQuantity: Int,
        expectedArrivalDate: LocalDate,
        warehouseCode: String = "MAIN"
    ) = InboundShipment(
        shipmentId, listOf(InboundShipmentLine(shipmentId, sku, expectedQuantity, expectedArrivalDate, warehouseCode))
    )

    private fun markConfirmed(vararg orderIds: String) {
        val claims = claimRepository.findAllById(orderIds.toList())
        Assertions.assertEquals(orderIds.size, claims.size)
        claims.forEach {
            it.orderReservationStatus = OrderReservationStatus.CONFIRMED
            it.confirmedAt = clock.instant()
        }
        claimRepository.saveAll(claims)
    }

    private fun setNow(instant: Instant) {
        Mockito.`when`(clock.instant()).thenReturn(instant)
    }

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>("pgvector/pgvector:pg16")

        @DynamicPropertySource
        @JvmStatic
        fun registerDatabaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
