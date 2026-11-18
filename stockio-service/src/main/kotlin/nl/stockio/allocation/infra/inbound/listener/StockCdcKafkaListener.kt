package nl.stockio.allocation.infra.inbound.listener

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.stockio.allocation.domain.fastpath.RedisKeys
import nl.stockio.allocation.infra.StockAvailabilityJpaRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

data class StockChange(val sku: String, val warehouseCode: String)

/**
 * Reads the SKU and warehouse from a Debezium envelope or a flat stock JSON object.
 * Returns null when either identity field is missing.
 */
object StockCdcEventParser {
    fun parse(payload: String, mapper: ObjectMapper): StockChange? {
        val root = mapper.readTree(payload)
        val row = root.path("payload").path("after").takeUnless { it.isMissingNode || it.isNull }
            ?: root.path("after").takeUnless { it.isMissingNode || it.isNull }
            ?: root
        val sku = text(row, "sku") ?: return null
        val warehouse = text(row, "warehouse_code") ?: text(row, "warehouseCode") ?: return null
        return StockChange(sku, warehouse)
    }

    private fun text(node: JsonNode, field: String): String? =
        node.path(field).takeUnless { it.isMissingNode || it.isNull }?.asText()?.takeIf { it.isNotBlank() }
}

/**
 * Copies committed PostgreSQL stock availability back into Redis.
 *
 * [AllocationJobRepository.accept] only decrements Redis for the request in
 * hand. Later ledger writes (allocation, expiry, receipt, ledger sync) change
 * `public.stock` without passing through that accept path. This listener replaces
 * `stock:sku:{sku}:{warehouseCode}` with `SUM(quantity_available)` after each
 * committed change so the fast path converges on the ledger.
 *
 * Stockio does not publish these events. Debezium Connect tails the PostgreSQL
 * write-ahead log for `public.stock` and writes them to
 * `stockio.allocation.redis.cdc.topic` (default `stockio.cdc.public.stock`). The
 * connector definition is `infra/docker/debezium/stock-connector.json`. The
 * listener stays inactive unless `stockio.allocation.redis.cdc.enabled` is true and a
 * [StringRedisTemplate] bean exists.
 *
 * Replay is safe: each event sets the absolute sum. Hold keys are left untouched.
 */
@Component
@ConditionalOnBean(StringRedisTemplate::class)
@ConditionalOnProperty(prefix = "stockio.allocation.redis.cdc", name = ["enabled"], havingValue = "true")
class StockCdcKafkaListener(
    private val objectMapper: ObjectMapper,
    private val availability: StockAvailabilityJpaRepository,
    private val redis: StringRedisTemplate
) {
    @KafkaListener(
        id = "stockioStockCdcListener",
        topics = ["\${stockio.allocation.redis.cdc.topic:stockio.cdc.public.stock}"],
        groupId = "\${stockio.allocation.redis.cdc.group-id:stockio-stock-cdc}",
        autoStartup = "true"
    )
    /**
     * Applies one stock change by rewriting Redis availability for that SKU and warehouse.
     * Ignores payloads that do not identify both.
     */
    fun receive(payload: String) {
        val change = StockCdcEventParser.parse(payload, objectMapper) ?: return
        val available = availability.sumAvailable(change.sku, change.warehouseCode) ?: 0L
        redis.opsForValue().set(RedisKeys.stock(change.sku, change.warehouseCode), available.toString())
    }
}
