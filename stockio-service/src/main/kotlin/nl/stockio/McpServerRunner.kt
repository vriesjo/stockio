package nl.stockio

import nl.stockio.logistics.mcp.LogisticsMcpTools
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class McpServerRunner {

    @Bean
    fun toolCallbacks(logisticsMcpTools: LogisticsMcpTools): List<ToolCallback> =
        ToolCallbacks.from(logisticsMcpTools).toList()
}

fun main(args: Array<String>) {
    SpringApplication.run(McpServerRunner::class.java, *args)
}
