package nl.stockio

import nl.stockio.allocation.application.tool.AllocationMcpTools
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class StockioApplication {

    @Bean
    fun toolCallbacks(
        allocationTools: AllocationMcpTools
    ): List<ToolCallback> =
        ToolCallbacks.from(allocationTools).toList()
}

fun main(args: Array<String>) {
    SpringApplication.run(StockioApplication::class.java, *args)
}
