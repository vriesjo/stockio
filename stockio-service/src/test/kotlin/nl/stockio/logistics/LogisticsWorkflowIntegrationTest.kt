package nl.stockio.logistics

import nl.stockio.logistics.domain.AllocationRequest
import nl.stockio.logistics.domain.AllocationRunType
import nl.stockio.logistics.domain.AllocationStatus
import nl.stockio.logistics.domain.BackorderFulfillmentRequest
import nl.stockio.logistics.domain.DemandOrder
import nl.stockio.logistics.domain.InboundShipment
import nl.stockio.logistics.domain.ProcessDailyStockRequest
import nl.stockio.logistics.persistence.AllocationRecordRepository
import nl.stockio.logistics.persistence.AllocationRunRepository
import nl.stockio.logistics.persistence.AllocationSourceRepository
import nl.stockio.logistics.persistence.OpenBackorderRepository
import nl.stockio.logistics.persistence.StockDailySnapshotRepository
import nl.stockio.logistics.persistence.StockDailyLotSnapshotRepository
import nl.stockio.logistics.persistence.StockEntity
import nl.stockio.logistics.persistence.StockRepository
import nl.stockio.logistics.persistence.StockReservationEntity
import nl.stockio.logistics.persistence.StockReservationRepository
import nl.stockio.logistics.service.AllocationCsvLoader
import nl.stockio.logistics.service.DailyStockLedgerService
import nl.stockio.logistics.service.ExcelReportGeneratorService
import nl.stockio.logistics.service.LogisticsAllocationService
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsWorkflowIntegrationTest {

    @Autowired
    private lateinit var allocationService: LogisticsAllocationService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var reportGenerator: ExcelReportGeneratorService

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
    private lateinit var dailyStockLedgerService: DailyStockLedgerService

    @Autowired
    private lateinit var allocationCsvLoader: AllocationCsvLoader

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
        assertEquals(32, productCount)
        assertEquals(
            295L,
            jdbcTemplate.queryForObject(
                "SELECT price_cents FROM products WHERE sku = ?",
                Long::class.java,
                "OZEKI-SAKE-DRY"
            ) ?: error("The seeded Ozeki product has no price.")
        )
        assertEquals(
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

        val request = allocationCsvLoader.load(csvPath        )

        assertEquals(LocalDate.now(), request.asOfDate)
        assertEquals("MAKI-ZALM", request.demandOrders.single().sku)
        assertEquals(327, request.demandOrders.single().requestedQuantity)
        assertEquals(LocalDate.parse("2024-01-01"), request.demandOrders.single().placedDate)
        assertEquals(request.demandOrders.single().placedDate, request.demandOrders.single().requiredDeliveryDate)
        assertEquals(1, request.demandOrders.single().priority)
        assertTrue(request.demandOrders.single().customer.isNotBlank())
        assertEquals(request.demandOrders.single().orderId, UUID.fromString(request.demandOrders.single().orderId).toString())
        assertEquals("MAKI-ZALM", request.inboundShipments.single().sku)
        assertEquals(376, request.inboundShipments.single().expectedQuantity)
        assertEquals(LocalDate.parse("2024-01-01"), request.inboundShipments.single().expectedArrivalDate)
        assertEquals("MAIN", request.inboundShipments.single().warehouseCode)
        assertEquals(
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

        mockMvc.perform(
            post("/api/logistics/allocate-and-sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "requestId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                          "asOfDate": "$today",
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
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.allocationRun.allocations[0].allocatedFromInventory").value(2))
            .andExpect(jsonPath("$.stockSync.snapshotDate").value(today.toString()))
            .andExpect(jsonPath("$.stockSync.lotCount").value(1))

        val updatedStock = stockRepository.findAll().single()
        assertEquals(3, updatedStock.quantityOnHand)
        assertEquals(0, updatedStock.quantityReserved)
    }

    @Test
    fun `REST endpoint rejects non-today allocation before creating a run`() {
        val today = LocalDate.now()

        mockMvc.perform(
            post("/api/logistics/allocate-and-sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "requestId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                          "asOfDate": "${today.plusDays(1)}",
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
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Allocation and stock synchronization require asOfDate to be today."))

        assertEquals(0, runRepository.count())
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
            )
        )
        val initialRun = allocationService.calculateAllocation(allocationRequest)
        val retryRun = allocationService.calculateAllocation(allocationRequest)

        assertEquals(initialRun.runId, retryRun.runId)
        assertEquals(2, retryRun.openBackorderCount)
        assertThrows(IllegalArgumentException::class.java) {
            allocationService.calculateAllocation(
                allocationRequest.copy(
                    demandOrders = allocationRequest.demandOrders.map {
                        if (it.orderId == "ORDER-HIGH") it.copy(customer = "Changed Customer") else it
                    }
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            allocationService.calculateAllocation(
                allocationRequest.copy(
                    requestId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    demandOrders = listOf(allocationRequest.demandOrders.first())
                )
            )
        }
        assertEquals(AllocationRunType.NEW_DEMAND, initialRun.runType)
        assertEquals(
            listOf("ORDER-HIGH", "ORDER-FIFO-1", "ORDER-FIFO-2"),
            initialRun.allocations.map { it.orderId }
        )
        assertEquals(3, initialRun.allocations[0].allocatedFromInventory)
        assertEquals(2, initialRun.allocations[0].allocatedFromInbound)
        assertEquals("LOT-OLD", initialRun.allocations[0].sources.first().sourceId)
        assertEquals(AllocationStatus.PARTIALLY_BACKORDERED, initialRun.allocations[1].status)
        assertEquals(3, initialRun.allocations[1].backorderedQuantity)
        assertEquals(AllocationStatus.BACKORDERED, initialRun.allocations[2].status)
        assertEquals(5, initialRun.allocations[2].backorderedQuantity)
        assertEquals(2, initialRun.openBackorderCount)
        assertEquals(2, backorderRepository.count())
        assertEquals(3, stockRepository.findAll().sumOf { it.quantityReserved })
        assertEquals(3, stockReservationRepository.findAll().sumOf { it.quantity })

        stockRepository.save(
            StockEntity(
                sku = "MAKI-ZALM",
                lotId = "LOT-RESTOCK",
                warehouseCode = "B-01",
                receivedDate = LocalDate.parse("2026-10-18"),
                quantityOnHand = 6
            )
        )
        val fulfillmentRequest = BackorderFulfillmentRequest(
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
            )
        )
        val fulfillmentRun = allocationService.fulfillOpenBackorders(fulfillmentRequest)
        val fulfillmentRetry = allocationService.fulfillOpenBackorders(fulfillmentRequest)

        assertEquals(fulfillmentRun.runId, fulfillmentRetry.runId)
        assertEquals(AllocationRunType.BACKORDER_FULFILLMENT, fulfillmentRun.runType)
        assertEquals(1, fulfillmentRun.openBackorderCount)
        assertEquals(2, allocationService.getRun(initialRun.runId).openBackorderCount)
        assertEquals("ORDER-FIFO-1", fulfillmentRun.allocations[0].orderId)
        assertEquals(AllocationStatus.FULFILLED, fulfillmentRun.allocations[0].status)
        assertEquals("ORDER-FIFO-2", fulfillmentRun.allocations[1].orderId)
        assertEquals(AllocationStatus.PARTIALLY_FULFILLED, fulfillmentRun.allocations[1].status)
        assertEquals(1, fulfillmentRun.allocations[1].allocatedFromInbound)
        assertEquals(1, fulfillmentRun.allocations[1].backorderedQuantity)
        assertEquals(1, fulfillmentRun.openBackorderCount)

        val report = reportGenerator.generate(
            allocationService.getRun(initialRun.runId),
            temporaryDirectory.resolve("allocation.xlsx")
        )
        assertTrue(Files.exists(report))
        XSSFWorkbook(Files.newInputStream(report)).use { workbook ->
            assertEquals(4, workbook.getSheet("Allocation Details").physicalNumberOfRows)
            assertEquals(5, workbook.getSheet("Source Allocations").physicalNumberOfRows)
            assertEquals(2, workbook.getSheet("Pivot Summary").pivotTables.size)
            val formulaSummary = workbook.getSheet("Formula Summary")
            assertEquals(4, formulaSummary.physicalNumberOfRows)
            assertEquals(
                "SUMIFS('Allocation Details'!H:H,'Allocation Details'!D:D,A2,'Allocation Details'!L:L,B2)",
                formulaSummary.getRow(1).getCell(2).cellFormula
            )
            assertEquals(
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
        val result = allocationService.calculateAllocation(
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
                )
            )
        )

        assertEquals(3, result.allocations.last().backorderedQuantity)
        assertEquals(
            listOf(10, 6, 0),
            stockDailySnapshotRepository
                .findAllBySkuAndWarehouseCodeOrderBySnapshotDate("MAKI-ZALM", "MAIN")
                .map { it.closingStock }
        )
        assertEquals(
            listOf(0, 4, 6),
            stockDailySnapshotRepository
                .findAllBySkuAndWarehouseCodeOrderBySnapshotDate("MAKI-ZALM", "MAIN")
                .map { it.allocatedQuantity }
        )
        assertEquals(
            3,
            stockDailySnapshotRepository.findBySnapshotDateAndSkuAndWarehouseCode(
                today.plusDays(2),
                "MAKI-ZALM",
                "MAIN"
            )?.shortageQuantity ?: error("The backorder shortage snapshot is missing.")
        )
        assertEquals(10, stockRepository.findAll().single().quantityOnHand)
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
        allocationService.calculateAllocation(
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
                )
            )
        )

        val syncRequest = ProcessDailyStockRequest(today)
        dailyStockLedgerService.process(syncRequest)
        dailyStockLedgerService.process(syncRequest)

        val stock = stockRepository.findAll().single()
        assertEquals(3, stock.quantityOnHand)
        assertEquals(2, stock.quantityReserved)
        assertEquals(listOf("FUTURE-ORDER"), stockReservationRepository.findAll().map { it.orderId })
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
            )
        )
        allocationService.calculateAllocation(orderRequest)

        dailyStockLedgerService.process(ProcessDailyStockRequest(today))
        dailyStockLedgerService.process(ProcessDailyStockRequest(today))

        val stock = stockRepository.findAll().single()
        assertEquals("ARRIVED-SHIPMENT", stock.lotId)
        assertEquals(today, stock.receivedDate)
        assertEquals(5, stock.quantityOnHand)
        assertEquals(3, stock.quantityReserved)
        assertEquals("INBOUND-ORDER", stockReservationRepository.findAll().single().orderId)
        assertEquals(
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
        allocationService.calculateAllocation(
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
                inboundShipments = listOf(shipment)
            )
        )

        val allocation = allocationService.calculateAllocation(
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
                )
            )
        ).allocations.single()

        assertEquals(4, allocation.allocatedFromInbound)
        assertEquals(0, allocation.backorderedQuantity)
        dailyStockLedgerService.process(ProcessDailyStockRequest(today))
        assertEquals(0, stockRepository.count())
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
            )
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
            )
        )

        assertEquals(4, allocationService.calculateAllocation(firstRequest).allocations.single().allocatedFromInventory)
        val secondAllocation = allocationService.calculateAllocation(secondRequest).allocations.single()

        assertEquals(1, secondAllocation.allocatedFromInventory)
        assertEquals(1, secondAllocation.backorderedQuantity)
        assertEquals(5, stockRepository.findAll().single().quantityReserved)
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
