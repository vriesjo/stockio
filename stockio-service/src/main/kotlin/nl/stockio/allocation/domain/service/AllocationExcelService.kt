package nl.stockio.allocation.domain.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID
import kotlin.collections.plusAssign
import kotlin.random.Random
import nl.stockio.allocation.domain.api.data.AllocationRequest
import nl.stockio.allocation.domain.api.data.AllocationRunType
import nl.stockio.allocation.domain.api.data.DemandOrder
import nl.stockio.allocation.domain.api.data.DemandOrderLine
import nl.stockio.allocation.domain.api.data.InboundShipment
import nl.stockio.allocation.domain.api.data.InboundShipmentLine
import nl.stockio.product.infra.ProductRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class AllocationExcelService(
    private val productRepository: ProductRepository,
    private val clock: Clock
) {

    fun loadCsvAndCreateAllocationRequest(path: Path): AllocationRequest {
        logger.info { "Loading allocation request from CSV '$path'." }
        val asOfDate = LocalDate.now(clock)
        val demandOrders = mutableListOf<DemandOrder>()
        val inboundShipments = mutableListOf<InboundShipment>()
        val skuByTitle = mutableMapOf<String, String>()
        val fileHash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                fileHash.update(buffer, 0, count)
            }
        }
        val importId = fileHash.digest().joinToString("") { "%02x".format(it) }

        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            val header = reader.readLine()
                ?.removePrefix("\uFEFF")
                ?.let(::parseCsvLine)
                ?: throw IllegalArgumentException("CSV file '$path' is empty.")
            require(header == EXPECTED_HEADER) {
                "CSV file '$path' must have these columns in order: ${EXPECTED_HEADER.joinToString(", ")}."
            }

            var lineNumber = 1
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber++
                if (line.isBlank()) continue

                val columns = parseCsvLine(line)
                require(columns.size == EXPECTED_HEADER.size) {
                    "CSV row $lineNumber must contain ${EXPECTED_HEADER.size} columns."
                }

                val title = columns[0]
                val documentId = UUID.nameUUIDFromBytes(
                    "stockio:csv:$importId:row:$lineNumber".toByteArray(StandardCharsets.UTF_8)
                ).toString()
                when (columns[1]) {
                    "Verkoop" -> {
                        val sku = findSku(title, path, lineNumber, skuByTitle)
                        val quantity = parseQuantity(columns[2], lineNumber)
                        val date = parseDate(columns[3], lineNumber)
                        demandOrders += DemandOrder(
                            orderId = documentId,
                            customer = randomCustomerName(documentId.hashCode()),
                            priority = 1,
                            placedDate = date,
                            lines = listOf(DemandOrderLine(documentId, sku, quantity, date))
                        )
                    }

                    "Levering" -> {
                        val sku = findSku(title, path, lineNumber, skuByTitle)
                        val quantity = parseQuantity(columns[2], lineNumber)
                        val date = parseDate(columns[3], lineNumber)
                        inboundShipments += InboundShipment(
                            shipmentId = documentId,
                            lines = listOf(InboundShipmentLine(documentId, sku, quantity, date, "MAIN"))
                        )
                    }

                    else -> logger.warn {
                        "Skipping CSV row $lineNumber with unsupported transaction type '${columns[1]}'."
                    }
                }
            }
        }

        if (demandOrders.isEmpty()) {
            val message = "CSV file '$path' contains no rows with transaction type 'Verkoop'."
            logger.warn { message }
            throw IllegalArgumentException(message)
        }
        val request = AllocationRequest(
            // Same-date reloads replay; another date must not reclaim the same documents.
            requestId = UUID.nameUUIDFromBytes(
                "stockio:csv:request:$importId:$asOfDate".toByteArray(StandardCharsets.UTF_8)
            ),
            asOfDate = asOfDate,
            demandOrders = demandOrders,
            inboundShipments = inboundShipments,
            runType = AllocationRunType.NEW_DEMAND
        )
        logger.info {
            "Loaded allocation request from CSV '$path': ${demandOrders.size} demand orders and " +
                "${inboundShipments.size} inbound shipments."
        }
        return request
    }

    private fun findSku(
        title: String,
        path: Path,
        lineNumber: Int,
        skuByTitle: MutableMap<String, String>
    ): String {
        val key = title.lowercase(Locale.ROOT)
        return skuByTitle.getOrPut(key) {
            productRepository.findSkuByTitle(title)
                ?: throw IllegalArgumentException(
                    "No product with title '$title' was found for CSV row $lineNumber in '$path'."
                )
        }
    }

    private fun parseQuantity(value: String, lineNumber: Int): Int {
        val quantity = value.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid quantity '$value' in CSV row $lineNumber.")
        require(quantity > 0) { "Quantity in CSV row $lineNumber must be greater than zero." }
        return quantity
    }

    private fun parseDate(value: String, lineNumber: Int): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(value, DASHED_DAY_FIRST_DATE_FORMAT)
            } catch (exception: DateTimeParseException) {
                throw IllegalArgumentException(
                    "Invalid date '$value' in CSV row $lineNumber; expected yyyy-MM-dd or d-M-yyyy with a valid calendar date.",
                    exception
                )
            }
        }

    private fun randomCustomerName(seed: Int): String {
        val random = Random(seed)
        val firstName = FIRST_NAMES[random.nextInt(FIRST_NAMES.size)]
        val lastName = LAST_NAMES[random.nextInt(LAST_NAMES.size)]
        return "$firstName $lastName"
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' && quoted && line.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }

                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    fields += field.toString().trim()
                    field.setLength(0)
                }

                else -> field.append(character)
            }
            index++
        }

        require(!quoted) { "CSV row contains an unterminated quoted field." }
        fields += field.toString().trim()
        return fields
    }

    private companion object {
        val EXPECTED_HEADER = listOf("Artikel", "Transactie_type", "QTY", "Datum")
        val DASHED_DAY_FIRST_DATE_FORMAT = DateTimeFormatter
            .ofPattern("d-M-uuuu")
            .withResolverStyle(ResolverStyle.STRICT)
        val FIRST_NAMES = listOf("Alex", "Jamie", "Morgan", "Riley", "Sam", "Taylor")
        val LAST_NAMES = listOf("Bakker", "De Vries", "Jansen", "Smit", "Visser", "Willems")
    }
}
