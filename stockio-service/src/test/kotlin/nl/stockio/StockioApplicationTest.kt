package nl.stockio

import nl.stockio.allocation.domain.tools.AllocationMcpTools
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class StockioApplicationTest {

    @Test
    fun `MCP registration retains allocation tools without product allocation tool`() {
        val tools = StockioApplication().toolCallbacks(mock(AllocationMcpTools::class.java))
            .map { it.toolDefinition.name() }

        assertTrue("allocation_and_stock_projection" in tools)
        assertTrue("allocate_open_backorders" in tools)
        assertFalse("allocate_product_by_name" in tools)
    }
}
