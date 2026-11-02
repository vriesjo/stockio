package nl.stockio

import nl.stockio.allocation.tools.AllocationTools
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class McpServerRunner {

    @Bean
    fun toolCallbacks(
        allocationTools: AllocationTools
    ): List<ToolCallback> =
        ToolCallbacks.from(allocationTools).toList()
}

fun main(args: Array<String>) {
    SpringApplication.run(McpServerRunner::class.java, *args)
}
