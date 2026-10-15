package nl.stockio.allocation.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.bean.AllocationJobAccepted
import nl.stockio.allocation.domain.bean.AllocationRequest
import nl.stockio.allocation.domain.bean.AllocationRunType
import nl.stockio.allocation.domain.bean.AllocationStatus
import nl.stockio.allocation.domain.bean.DemandOrder
import nl.stockio.allocation.domain.bean.InboundShipment
import nl.stockio.allocation.domain.bean.ProcessDailyStockRequest
import nl.stockio.allocation.domain.processor.ExcelReportProcessor
import nl.stockio.allocation.domain.processor.StockLedgerProcessor
import nl.stockio.allocation.domain.processor.StockProjectionProcessor
import nl.stockio.allocation.infra.AllocationRecordRepository
import nl.stockio.allocation.infra.AllocationRunRepository
import nl.stockio.allocation.infra.AllocationSourceRepository
import nl.stockio.allocation.infra.OpenBackorderRepository
import nl.stockio.allocation.infra.StockDailyLotSnapshotRepository
import nl.stockio.allocation.infra.StockDailySnapshotRepository
import nl.stockio.allocation.infra.StockEntity
import nl.stockio.allocation.infra.StockRepository
import nl.stockio.allocation.infra.StockReservationRepository
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.awaitility.Awaitility
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest(
    properties = [
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "stockio.allocation.rabbitmq.publisher.enabled=false"
    ]
)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AllocationIntegrationTest {

    @Autowired
    private lateinit var allocationService: AllocationService

    @Autowired
    private lateinit var allocationRunService: AllocationRunService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var reportGenerator: ExcelReportProcessor

    @Autowired
    private lateinit var sourceRepository: AllocationSourceRepository

    @Autowired
    private lateinit var recordRepository: AllocationRecordRepository

    @Autowired
    private lateinit var runRepository: AllocationRunRepository

    @Autowired
    private lateinit var backorderRepository: OpenBackorderRepository

    @Autowired
    private lateinit var stockDailyLotSnapshotRepository: StockDailyLotSnapshotRepository

    @Autowired
    private lateinit var stockDailySnapshotRepository: StockDailySnapshotRepository

    @Autowired
    private lateinit var stockRepository: StockRepository

    @Autowired
    private lateinit var stockReservationRepository: StockReservationRepository

    @Autowired
    private lateinit var stockLedgerProcessor: StockLedgerProcessor

    @Autowired
    private lateinit var stockProjectionProcessor: StockProjectionProcessor

    @Autowired
    private lateinit var allocationExcelService: AllocationExcelService

    @Autowired
    private lateinit var mockMvc: MockMvc

    @TempDir
    lateinit var temporaryDirectory: Path

    @BeforeEach
    fun clearDatabase() {
        stockDailyLotSnapshotRepository.deleteAll()
        stockDailySnapshotRepository.deleteAll()
        jdbcTemplate.update("DELETE FROM inbound_reservation")
        jdbcTemplate.update("DELETE FROM inbound_supply")
        stockReservationRepository.deleteAll()
        stockRepository.deleteAll()
        sourceRepository.deleteAll()
        recordRepository.deleteAll()
        runRepository.deleteAll()
        backorderRepository.deleteAll()
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
        Assertions.assertEquals("MAKI-ZALM", request.demandOrders.single().sku)
        Assertions.assertEquals(327, request.demandOrders.single().requestedQuantity)
        Assertions.assertEquals(LocalDate.parse("2024-01-01"), request.demandOrders.single().placedDate)
        Assertions.assertEquals(
            request.demandOrders.single().placedDate,
            request.demandOrders.single().requiredDeliveryDate
        )
        Assertions.assertEquals(1, request.demandOrders.single().priority)
        Assertions.assertTrue(request.demandOrders.single().customer.isNotBlank())
        Assertions.assertEquals(
            request.demandOrders.single().orderId,
            UUID.fromString(request.demandOrders.single().orderId).toString()
        )
        Assertions.assertEquals("MAKI-ZALM", request.inboundShipments.single().sku)
        Assertions.assertEquals(376, request.inboundShipments.single().expectedQuantity)
        Assertions.assertEquals(LocalDate.parse("2024-01-01"), request.inboundShipments.single().expectedArrivalDate)
        Assertions.assertEquals("MAIN", request.inboundShipments.single().warehouseCode)
        Assertions.assertEquals(
            request.inboundShipments.single().shipmentId,
            UUID.fromString(request.inboundShipments.single().shipmentId).toString()
        )
    }

    @Test
    fun `REST endpoint allocates then synchronizes today's stock snapshot`() {
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
            MockMvcRequestBuilders.post("/api/logistics/allocate-project-and-sync")
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
                              "sku": "MAKI-ZALM",
                              "requestedQuantity": 2,
                              "priority": 1,
                              "placedDate": "$today",
                              "requiredDeliveryDate": "$today"
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
            "/api/logistics/allocate-project-and-sync/job-status/$jobId",
            acceptedResponse.response.getHeader("Location")
        )

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted {
            mockMvc.perform(
                MockMvcRequestBuilders
                    .get("/api/logistics/allocate-project-and-sync/job-status/$jobId")
            )
                .andExpect(MockMvcResultMatchers.status().isOk)
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(
                    MockMvcResultMatchers.jsonPath("$.result.allocationRun.allocations[0].allocatedFromInventory").value(2))
                .andExpect(MockMvcResultMatchers.jsonPath("$.result.stockSync.snapshotDate").value(today.toString()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.result.stockSync.lotCount").value(1))
        }

        val updatedStock = stockRepository.findAll().single()
        Assertions.assertEquals(3, updatedStock.quantityOnHand)
        Assertions.assertEquals(0, updatedStock.quantityReserved)
    }

    @Test
    fun `REST endpoint rejects non-today allocation before creating a run`() {
        val today = LocalDate.now()

        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/logistics/allocate-project-and-sync")
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
                              "sku": "MAKI-ZALM",
                              "requestedQuantity": 1,
                              "priority": 1,
                              "placedDate": "$today",
                              "requiredDeliveryDate": "${today.plusDays(1)}"
                            }
                          ]
                        }
                    """.trimIndent()
                )
        )
            .andExpect(MockMvcResultMatchers.status().isBadRequest)
            .andExpect(MockMvcResultMatchers.jsonPath("$.error").value("Allocation and stock synchronization require asOfDate to be today."))

        Assertions.assertEquals(0, runRepository.count())
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
                DemandOrder(
                    orderId = "ORDER-HIGH",
                    customer = "Priority Customer",
                    sku = "MAKI-ZALM",
                    requestedQuantity = 5,
                    priority = 1,
                    placedDate = LocalDate.parse("2026-10-01"),
                    requiredDeliveryDate = LocalDate.parse("2026-10-20")
                ),
                DemandOrder(
                    orderId = "ORDER-FIFO-1",
                    customer = "First Customer",
                    sku = "MAKI-ZALM",
                    requestedQuantity = 5,
                    priority = 2,
                    placedDate = LocalDate.parse("2026-10-02"),
                    requiredDeliveryDate = LocalDate.parse("2026-10-15")
                ),
                DemandOrder(
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
                InboundShipment(
                    shipmentId = "SHIP-ON-TIME",
                    sku = "MAKI-ZALM",
                    expectedQuantity = 4,
                    expectedArrivalDate = LocalDate.parse("2026-10-14")
                ),
                InboundShipment(
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
                InboundShipment(
                    shipmentId = "SHIP-ARRIVED",
                    sku = "MAKI-ZALM",
                    expectedQuantity = 1,
                    expectedArrivalDate = LocalDate.parse("2026-10-14")
                ),
                InboundShipment(
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
        Assertions.assertEquals(2, allocationRunService.getRun(initialRun.runId).openBackorderCount)
        Assertions.assertEquals("ORDER-FIFO-1", fulfillmentRun.allocations[0].orderId)
        Assertions.assertEquals(AllocationStatus.FULFILLED, fulfillmentRun.allocations[0].status)
        Assertions.assertEquals("ORDER-FIFO-2", fulfillmentRun.allocations[1].orderId)
        Assertions.assertEquals(AllocationStatus.PARTIALLY_FULFILLED, fulfillmentRun.allocations[1].status)
        Assertions.assertEquals(1, fulfillmentRun.allocations[1].allocatedFromInbound)
        Assertions.assertEquals(1, fulfillmentRun.allocations[1].backorderedQuantity)
        Assertions.assertEquals(1, fulfillmentRun.openBackorderCount)

        val report = reportGenerator.process(
            allocationRunService.getRun(initialRun.runId),
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
                "SUMIFS('Allocation Details'!H:H,'Allocation Details'!D:D,A2,'Allocation Details'!L:L,B2)",
                formulaSummary.getRow(1).getCell(2).cellFormula
            )
            Assertions.assertEquals(
                "SUMIFS('Allocation Details'!K:K,'Allocation Details'!D:D,A2,'Allocation Details'!L:L,B2)",
                formulaSummary.getRow(1).getCell(5).cellFormula
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
                    DemandOrder(
                        orderId = "PROJECTION-ORDER-1",
                        customer = "First",
                        sku = "MAKI-ZALM",
                        requestedQuantity = 4,
                        priority = 1,
                        placedDate = today,
                        requiredDeliveryDate = today.plusDays(1)
                    ),
                    DemandOrder(
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
        stockProjectionProcessor.process(today)

        Assertions.assertEquals(3, result.allocations.last().backorderedQuantity)
        Assertions.assertEquals(
            listOf(10, 6, 0),
            stockDailySnapshotRepository
                .findAllBySkuAndWarehouseCodeOrderBySnapshotDate("MAKI-ZALM", "MAIN")
                .map { it.closingStock }
        )
        Assertions.assertEquals(
            listOf(0, 4, 6),
            stockDailySnapshotRepository
                .findAllBySkuAndWarehouseCodeOrderBySnapshotDate("MAKI-ZALM", "MAIN")
                .map { it.allocatedQuantity }
        )
        Assertions.assertEquals(
            3,
            stockDailySnapshotRepository.findBySnapshotDateAndSkuAndWarehouseCode(
                today.plusDays(2),
                "MAKI-ZALM",
                "MAIN"
            )?.shortageQuantity ?: error("The backorder shortage snapshot is missing.")
        )
        Assertions.assertEquals(10, stockRepository.findAll().single().quantityOnHand)
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
                    DemandOrder(
                        orderId = "DUE-ORDER",
                        customer = "Due",
                        sku = "MAKI-ZALM",
                        requestedQuantity = 3,
                        priority = 1,
                        placedDate = today,
                        requiredDeliveryDate = today
                    ),
                    DemandOrder(
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
                DemandOrder(
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
                InboundShipment(
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
        Assertions.assertEquals("ARRIVED-SHIPMENT", stock.lotId)
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
        val shipment = InboundShipment(
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
                    DemandOrder(
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
                    DemandOrder(
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
                DemandOrder(
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
                DemandOrder(
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

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun registerDatabaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
