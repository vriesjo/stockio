package nl.stockio.harness.performance

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.gatling.javaapi.core.ChainBuilder
import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.constantUsersPerSec
import io.gatling.javaapi.core.CoreDsl.csv
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.CoreDsl.percent
import io.gatling.javaapi.core.CoreDsl.rampUsersPerSec
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.ScenarioBuilder
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import io.gatling.javaapi.http.HttpProtocolBuilder
import io.gatling.javaapi.core.Session
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.DemandOrder
import nl.stockio.api.dto.DemandOrderLine
import nl.stockio.api.dto.InboundShipment
import nl.stockio.api.dto.InboundShipmentLine

private object AllocationOpenApiContract {
    // Keep synchronized with stockio-api/src/main/resources/openapi/allocation-controller.yaml
    const val CREATE_ALLOCATION_JOB_PATH = "/api/allocation/jobs"
    const val GET_ALLOCATION_JOB_STATUS_PATH = "/api/allocation/jobs/#{jobId}"
}

private data class PerformanceConfig(
    val protocol: String,
    val host: String,
    val port: Int,
    val asyncQuery: Boolean,
    val authToken: String?,
    val warmupUsersPerSecond: Double,
    val warmupDuration: Duration,
    val rampStartUsersPerSecond: Double,
    val rampEndUsersPerSecond: Double,
    val rampDuration: Duration,
    val soakUsersPerSecond: Double,
    val soakDuration: Duration,
    val p95MillisThreshold: Int,
    val p99MillisThreshold: Int,
    val successPercentThreshold: Double
) {
    val baseUrl: String = "$protocol://$host:$port"
}

private object ConfigLoader {
    fun load(): PerformanceConfig {
        val protocol = read("stockio.perf.protocol", "STOCKIO_PERF_PROTOCOL") ?: "http"
        val host = read("stockio.perf.host", "STOCKIO_PERF_HOST") ?: "127.0.0.1"
        val port = read("stockio.perf.port", "STOCKIO_PERF_PORT")?.toInt() ?: 9090
        val asyncQuery = read("stockio.perf.async", "STOCKIO_PERF_ASYNC")?.toBooleanStrictOrNull() ?: true
        val authToken = read("stockio.perf.authToken", "STOCKIO_PERF_AUTH_TOKEN")?.takeIf { it.isNotBlank() }
        return PerformanceConfig(
            protocol = protocol,
            host = host,
            port = port,
            asyncQuery = asyncQuery,
            authToken = authToken,
            warmupUsersPerSecond = read("stockio.perf.warmupRps", "STOCKIO_PERF_WARMUP_RPS")?.toDouble() ?: 5.0,
            warmupDuration = Duration.ofSeconds(
                read("stockio.perf.warmupSeconds", "STOCKIO_PERF_WARMUP_SECONDS")?.toLong() ?: 30L
            ),
            rampStartUsersPerSecond = read("stockio.perf.rampStartRps", "STOCKIO_PERF_RAMP_START_RPS")?.toDouble() ?: 10.0,
            rampEndUsersPerSecond = read("stockio.perf.rampEndRps", "STOCKIO_PERF_RAMP_END_RPS")?.toDouble() ?: 100.0,
            rampDuration = Duration.ofSeconds(
                read("stockio.perf.rampSeconds", "STOCKIO_PERF_RAMP_SECONDS")?.toLong() ?: 120L
            ),
            soakUsersPerSecond = read("stockio.perf.soakRps", "STOCKIO_PERF_SOAK_RPS")?.toDouble() ?: 100.0,
            soakDuration = Duration.ofSeconds(
                read("stockio.perf.soakSeconds", "STOCKIO_PERF_SOAK_SECONDS")?.toLong() ?: 300L
            ),
            p95MillisThreshold = read("stockio.perf.sla.p95Millis", "STOCKIO_PERF_SLA_P95_MS")?.toInt() ?: 300,
            p99MillisThreshold = read("stockio.perf.sla.p99Millis", "STOCKIO_PERF_SLA_P99_MS")?.toInt() ?: 800,
            successPercentThreshold = read("stockio.perf.sla.successPct", "STOCKIO_PERF_SLA_SUCCESS_PCT")?.toDouble() ?: 99.0
        )
    }

    private fun read(systemProperty: String, envVar: String): String? =
        System.getProperty(systemProperty) ?: System.getenv(envVar)
}

private object ProtocolConfig {
    fun build(config: PerformanceConfig): HttpProtocolBuilder {
        var protocol = http
            .baseUrl(config.baseUrl)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("stockio-gatling-harness")
            .disableCaching()
        if (config.authToken != null) {
            protocol = protocol.header("Authorization", "Bearer ${config.authToken}")
        }
        return protocol
    }
}

private object AllocationJobRequests {
    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private val createRequestBody = StringBody { session ->
        val orderId = requireSessionString(session, "orderId")
        val customer = requireSessionString(session, "customer")
        val priority = requireSessionString(session, "priority").toInt()
        val orderLineId = requireSessionString(session, "orderLineId")
        val sku = requireSessionString(session, "sku")
        val requestedQuantity = requireSessionString(session, "requestedQuantity").toInt()
        val requiredDeliveryDate = LocalDate.parse(requireSessionString(session, "requiredDeliveryDate"))
        val shipmentId = requireSessionString(session, "shipmentId")
        val shipmentLineId = requireSessionString(session, "shipmentLineId")

        val request = AllocationRequest(
            requestId = UUID.fromString(requireSessionString(session, "requestId")),
            asOfDate = LocalDate.parse(requireSessionString(session, "asOfDate")),
            runType = AllocationRunType.NEW_DEMAND,
            demandOrders = listOf(
                DemandOrder(
                    orderId = orderId,
                    customer = customer,
                    priority = priority,
                    placedDate = LocalDate.parse(requireSessionString(session, "asOfDate")),
                    lines = listOf(
                        DemandOrderLine(
                            orderLineId = orderLineId,
                            sku = sku,
                            requestedQuantity = requestedQuantity,
                            requiredDeliveryDate = requiredDeliveryDate
                        )
                    )
                )
            ),
            inboundShipments = listOf(
                InboundShipment(
                    shipmentId = shipmentId,
                    lines = listOf(
                        InboundShipmentLine(
                            shipmentLineId = shipmentLineId,
                            sku = sku,
                            expectedQuantity = requestedQuantity,
                            expectedArrivalDate = LocalDate.parse(requireSessionString(session, "expectedArrivalDate")),
                            warehouseCode = "MAIN"
                        )
                    )
                )
            )
        )
        objectMapper.writeValueAsString(request)
    }

    private fun requireSessionString(session: Session, key: String): String =
        session.getString(key) ?: error("Missing session key: $key")

    fun submitAllocationJob(async: Boolean): ChainBuilder =
        exec(
            http("submit-allocation-job")
                .post(AllocationOpenApiContract.CREATE_ALLOCATION_JOB_PATH)
                .queryParam("async", async.toString())
                .body(createRequestBody)
                .check(status().`in`(202, 201, 200))
                .check(jsonPath("$.jobId").saveAs("jobId"))
                .check(jsonPath("$.requestId").saveAs("acceptedRequestId"))
        )

    fun fetchAllocationJobStatus(): ChainBuilder =
        exec(
            http("get-allocation-job-status")
                .get(AllocationOpenApiContract.GET_ALLOCATION_JOB_STATUS_PATH)
                .check(status().`in`(200, 404))
        )
}

private object AllocationJobScenario {
    fun build(config: PerformanceConfig): ScenarioBuilder {
        val feeder = csv("feeders/allocation-demand.csv").circular()
        return scenario("allocation-job-create-and-poll")
            .feed(feeder)
            .exec(
                exec { session ->
                    val asOfDate = LocalDate.now().toString()
                    session
                        .set("requestId", UUID.randomUUID().toString())
                        .set("asOfDate", asOfDate)
                        .set("requiredDeliveryDate", LocalDate.now().plusDays(3).toString())
                        .set("expectedArrivalDate", LocalDate.now().plusDays(1).toString())
                }
            )
            .exec(AllocationJobRequests.submitAllocationJob(config.asyncQuery))
            .exec(AllocationJobRequests.fetchAllocationJobStatus())
    }
}

class AllocationJobSimulation : Simulation() {
    private val config = ConfigLoader.load()
    private val protocol = ProtocolConfig.build(config)
    private val scenario = AllocationJobScenario.build(config)

    init {
        setUp(
            scenario.injectOpen(
                constantUsersPerSec(config.warmupUsersPerSecond).during(config.warmupDuration),
                rampUsersPerSec(config.rampStartUsersPerSecond).to(config.rampEndUsersPerSecond)
                    .during(config.rampDuration),
                constantUsersPerSec(config.soakUsersPerSecond).during(config.soakDuration)
            )
        )
            .protocols(protocol)
            .assertions(
                global().responseTime().percentile3().lt(config.p95MillisThreshold),
                global().responseTime().percentile4().lt(config.p99MillisThreshold),
                global().successfulRequests().percent().gte(config.successPercentThreshold)
            )
    }
}
