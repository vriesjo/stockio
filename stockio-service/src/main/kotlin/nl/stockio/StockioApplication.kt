package nl.stockio

import nl.stockio.allocation.tools.AllocationTools
import nl.stockio.product.tools.ProductAllocationTools
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Bean

@SpringBootApplication
@ComponentScan(
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ["nl\\.stockio\\.api\\..*", "nl\\.stockio\\.StockioMcpApplication"]
        )
    ]
)
class McpServerRunner {

    @Bean
    fun toolCallbacks(
        allocationTools: AllocationTools,
        productAllocationTools: ProductAllocationTools
    ): List<ToolCallback> =
        ToolCallbacks.from(allocationTools, productAllocationTools).toList()
}

fun main(args: Array<String>) {
    SpringApplication.run(McpServerRunner::class.java, *args)
}
