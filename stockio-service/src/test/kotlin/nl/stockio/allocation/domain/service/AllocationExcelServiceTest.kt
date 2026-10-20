package nl.stockio.allocation.domain.service

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.time.Clock
import nl.stockio.product.infra.ProductRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AllocationExcelServiceTest {

    @Test
    fun `CSV rows remain independent documents with stable line identities on reload`() {
        val products = mock(ProductRepository::class.java)
        `when`(products.findSkuByTitle("Maki Zalm")).thenReturn("MAKI-ZALM")
        val destination = Path.of("build", "allocation-import-${UUID.randomUUID()}.csv")
        Files.createDirectories(destination.parent)
        Files.writeString(
            destination,
            """
            Artikel,Transactie_type,QTY,Datum
            Maki Zalm,Verkoop,5,2026-10-21
            Maki Zalm,Verkoop,5,2026-10-21
            Maki Zalm,Levering,10,2026-10-21
            Maki Zalm,Levering,10,2026-10-21
            """.trimIndent()
        )
        try {
            val service = AllocationExcelService(products, Clock.systemDefaultZone())
            val first = service.loadCsvAndCreateAllocationRequest(destination)
            val second = service.loadCsvAndCreateAllocationRequest(destination)

            assertEquals(first, second)
            assertEquals(2, first.demandOrders.size)
            assertEquals(2, first.inboundShipments.size)
            assertNotEquals(first.demandOrders[0].orderId, first.demandOrders[1].orderId)
            assertNotEquals(
                first.demandOrders[0].lines.single().orderLineId,
                first.demandOrders[1].lines.single().orderLineId
            )
            assertNotEquals(
                first.inboundShipments[0].lines.single().shipmentLineId,
                first.inboundShipments[1].lines.single().shipmentLineId
            )
            assertEquals(listOf(5, 5), first.demandOrders.map { it.lines.single().requestedQuantity })
            assertEquals(listOf(10, 10), first.inboundShipments.map { it.lines.single().expectedQuantity })
        } finally {
            Files.deleteIfExists(destination)
        }
    }
}
