package nl.stockio.api

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(
    prefix = "stockio.mcp.server",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class McpStdioClientConfig {

    @Bean(destroyMethod = "close")
    fun mcpSyncClient(
        @Value("\${stockio.mcp.server.jar-path}") jarPath: String,
        @Value("\${stockio.mcp.server.java-command}") javaCommand: String,
        @Value("\${stockio.mcp.server.request-timeout}") requestTimeout: Duration,
        @Value("\${stockio.mcp.server.initialization-timeout}") initializationTimeout: Duration
    ): McpSyncClient {
        val serverJar = Path.of(jarPath).toAbsolutePath().normalize()
        require(Files.isRegularFile(serverJar)) {
            "MCP server JAR was not found at $serverJar. Build it with :stockio-service:bootJar or set STOCKIO_MCP_SERVER_JAR."
        }

        val serverParameters = ServerParameters.builder(javaCommand)
            .args("-Dkotlin-logging.logStartupMessage=false", "-jar", serverJar.toString())
            .addEnvVar("SERVER_PORT", "0")
            .addEnvVar("SPRING_AI_MCP_SERVER_STDIO", "true")
            .addEnvVar("SPRING_PROFILES_ACTIVE", "stdio")
            .addEnvVar("JOBRUNR_DASHBOARD_ENABLED", "false")
            .build()
        val transport = StdioClientTransport(serverParameters, McpJsonMapper.getDefault())
        val client = McpClient.sync(transport)
            .requestTimeout(requestTimeout)
            .initializationTimeout(initializationTimeout)
            .build()
        try {
            client.initialize()
            return client
        } catch (exception: Exception) {
            client.close()
            throw exception
        }
    }
}
