package nl.stockio.allocation.domain.job.listener

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.schemaregistry.json.JsonSchema
import java.nio.ByteBuffer
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.schema.AllocationRequestSchema
import nl.stockio.allocation.domain.service.AllocationRequestLimits
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.util.backoff.FixedBackOff

@SpringJUnitConfig(classes = [AllocationKafkaOffsetIntegrationTest.TestConfiguration::class])
@EmbeddedKafka(
    partitions = 1,
    topics = [
        "stockio.allocation.offset-test",
        "stockio.allocation.invalid-test",
        "stockio.allocation.invalid-test.DLT",
        "stockio.allocation.retry-test",
        "stockio.allocation.retry-test.DLT"
    ]
)
class AllocationKafkaOffsetIntegrationTest {

    @Autowired
    private lateinit var broker: EmbeddedKafkaBroker

    private val mapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `does not commit offset until synchronous listener work completes`() {
        val topic = "stockio.allocation.offset-test"
        val scope = "allocation-offset-commit"
        val groupId = "stockio-allocation-offset-test"
        val topicPartition = TopicPartition(topic, 0)
        val submitted = java.util.concurrent.CountDownLatch(1)
        val completion = CompletableFuture<Void>()
        val request = request()
        registerSchema(scope, topic)
        val workQueue = Mockito.mock(AllocationKafkaWorkQueue::class.java)
        Mockito.`when`(workQueue.submit(request, request.requestId)).thenAnswer {
            submitted.countDown()
            completion
        }
        val listener = listener(workQueue)
        val container = container(groupId, topic, scope, listener)
        val (producerFactory, template) = requestTemplate(scope)
        val admin = AdminClient.create(
            mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to broker.brokersAsString)
        )

        try {
            container.start()
            ContainerTestUtils.waitForAssignment(container, 1)
            template.send(topic, request).get(5, TimeUnit.SECONDS)

            assertTrue(submitted.await(10, TimeUnit.SECONDS))
            assertNull(committedOffset(admin, groupId, topicPartition))

            completion.complete(null)
            awaitCommittedOffset(admin, groupId, topicPartition, 1L)
        } finally {
            completion.completeExceptionally(IllegalStateException("Test finished."))
            container.stop()
            producerFactory.destroy()
            admin.close()
        }
    }

    @Test
    fun `routes plain JSON and an unknown field to the dead-letter topic without retry`() {
        val sourceTopic = "stockio.allocation.invalid-test"
        val deadLetterTopic = "$sourceTopic.DLT"
        val scope = "allocation-invalid-record"
        val groupId = "stockio-allocation-invalid-test"
        val schemaId = registerSchema(scope, sourceTopic, deadLetterTopic)
        val workQueue = Mockito.mock(AllocationKafkaWorkQueue::class.java)
        val listener = listener(workQueue)
        val container = container(groupId, sourceTopic, scope, listener)
        val (schemaFactory, schemaTemplate) = requestTemplate(scope)
        val (byteFactory, byteTemplate) = byteTemplate()
        container.setCommonErrorHandler(
            errorHandler(schemaTemplate, byteTemplate, deadLetterTopic, FixedBackOff(0, 0))
        )
        val dltConsumer = byteConsumer("stockio-allocation-dlt-reader")
        val plain = "{".toByteArray()
        val unknown = frame(
            schemaId,
            """{"requestId":"${UUID.randomUUID()}","asOfDate":"2026-11-12","runType":"NEW_DEMAND","unexpected":true}"""
        )

        try {
            container.start()
            ContainerTestUtils.waitForAssignment(container, 1)
            dltConsumer.subscribe(listOf(deadLetterTopic))
            byteTemplate.send(sourceTopic, plain).get(5, TimeUnit.SECONDS)
            assertArrayEquals(plain, awaitRecord(dltConsumer).value())

            byteTemplate.send(sourceTopic, unknown).get(5, TimeUnit.SECONDS)
            assertArrayEquals(unknown, awaitRecord(dltConsumer).value())
            Mockito.verifyNoInteractions(workQueue)
        } finally {
            container.stop()
            dltConsumer.close()
            schemaFactory.destroy()
            byteFactory.destroy()
        }
    }

    @Test
    fun `retries failed handoff then routes exhausted record to dead-letter topic`() {
        val sourceTopic = "stockio.allocation.retry-test"
        val deadLetterTopic = "$sourceTopic.DLT"
        val scope = "allocation-retry-record"
        val groupId = "stockio-allocation-retry-test"
        val request = request()
        registerSchema(scope, sourceTopic, deadLetterTopic)
        val workQueue = Mockito.mock(AllocationKafkaWorkQueue::class.java)
        Mockito.`when`(workQueue.submit(request, request.requestId)).thenReturn(
            CompletableFuture.failedFuture(IllegalStateException("Durable handoff failed."))
        )
        val listener = listener(workQueue)
        val container = container(groupId, sourceTopic, scope, listener)
        val (producerFactory, template) = requestTemplate(scope)
        val (byteFactory, byteTemplate) = byteTemplate()
        container.setCommonErrorHandler(
            errorHandler(template, byteTemplate, deadLetterTopic, FixedBackOff(0, 1))
        )
        val dltConsumer = byteConsumer("stockio-allocation-retry-dlt-reader")
        val deserializer = AllocationJsonSchemaDeserializer(mapper)
        deserializer.configure(schemaConfig(scope), false)

        try {
            container.start()
            ContainerTestUtils.waitForAssignment(container, 1)
            dltConsumer.subscribe(listOf(deadLetterTopic))
            template.send(sourceTopic, request).get(5, TimeUnit.SECONDS)

            val deadLetter = awaitRecord(dltConsumer)
            assertEquals(request, deserializer.deserialize(deadLetterTopic, deadLetter.value()))
            Mockito.verify(workQueue, Mockito.times(2)).submit(request, request.requestId)
        } finally {
            container.stop()
            dltConsumer.close()
            producerFactory.destroy()
            byteFactory.destroy()
        }
    }

    private fun request() = AllocationRequest(UUID.randomUUID(), LocalDate.now(), AllocationRunType.NEW_DEMAND)

    private fun listener(workQueue: AllocationKafkaWorkQueue) = AllocationKafkaListener(
        Mockito.mock(AllocationRequestLimits::class.java),
        workQueue,
        Mockito.mock(nl.stockio.allocation.domain.job.AllocationJobTrackingService::class.java)
    )

    private fun schemaConfig(scope: String): MutableMap<String, Any> = hashMapOf(
        "schema.registry.url" to "mock://$scope",
        "auto.register.schemas" to false,
        "use.latest.version" to true,
        "latest.compatibility.strict" to false,
        "json.fail.invalid.schema" to true,
        "json.value.type" to AllocationRequest::class.java.name
    )

    private fun registerSchema(scope: String, vararg topics: String): Int {
        val serializer = AllocationJsonSchemaSerializer<AllocationRequest>(mapper)
        serializer.configure(schemaConfig(scope), false)
        val client = MockSchemaRegistry.getClientForScope(scope)
        val schema = JsonSchema(AllocationRequestSchema.text())
        var id = 0
        topics.forEach { topic ->
            id = client.register(AllocationRequestSchema.subject(topic), schema)
        }
        return id
    }

    private fun requestTemplate(
        scope: String
    ): Pair<DefaultKafkaProducerFactory<String, AllocationRequest>, KafkaTemplate<String, AllocationRequest>> {
        val properties = brokerProperties(KafkaTestUtils.producerProps(broker))
        properties.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)
        properties.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)
        properties.putAll(schemaConfig(scope))
        val factory = DefaultKafkaProducerFactory(
            properties,
            StringSerializer(),
            AllocationJsonSchemaSerializer<AllocationRequest>(mapper)
        )
        return factory to KafkaTemplate(factory)
    }

    private fun byteTemplate(): Pair<DefaultKafkaProducerFactory<String, ByteArray>, KafkaTemplate<String, ByteArray>> {
        val properties = brokerProperties(KafkaTestUtils.producerProps(broker))
        properties.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)
        properties.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)
        val factory = DefaultKafkaProducerFactory(
            properties,
            StringSerializer(),
            ByteArraySerializer()
        )
        return factory to KafkaTemplate(factory)
    }

    private fun container(
        groupId: String,
        topic: String,
        scope: String,
        listener: AllocationKafkaListener
    ): KafkaMessageListenerContainer<String, AllocationRequest> {
        val properties = brokerProperties(KafkaTestUtils.consumerProps(groupId, "false", broker))
        properties[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        properties[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
        properties.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)
        properties.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)
        properties.putAll(schemaConfig(scope))
        val consumerFactory = DefaultKafkaConsumerFactory(
            properties,
            StringDeserializer(),
            ErrorHandlingDeserializer(AllocationJsonSchemaDeserializer(mapper))
        )
        val containerProperties = ContainerProperties(topic).apply {
            this.groupId = groupId
            ackMode = ContainerProperties.AckMode.RECORD
        }
        return KafkaMessageListenerContainer(consumerFactory, containerProperties).also {
            it.setupMessageListener(MessageListener<String, AllocationRequest> { record -> listener.receive(record) })
        }
    }

    private fun errorHandler(
        schemaTemplate: KafkaTemplate<String, AllocationRequest>,
        bytesTemplate: KafkaTemplate<String, ByteArray>,
        deadLetterTopic: String,
        backOff: FixedBackOff
    ): DefaultErrorHandler {
        val templates: Map<Class<*>, KafkaOperations<*, *>> = linkedMapOf(
            AllocationRequest::class.java to schemaTemplate,
            ByteArray::class.java to bytesTemplate
        )
        val recoverer = DeadLetterPublishingRecoverer(templates) { record, _ ->
            TopicPartition(deadLetterTopic, record.partition())
        }.also { it.setFailIfSendResultIsError(true) }
        return DefaultErrorHandler(recoverer, backOff).also {
            it.addNotRetryableExceptions(
                IllegalArgumentException::class.java,
                org.apache.kafka.common.errors.SerializationException::class.java,
                org.springframework.kafka.support.serializer.DeserializationException::class.java
            )
        }
    }

    private fun byteConsumer(groupId: String): KafkaConsumer<String, ByteArray> {
        val properties = brokerProperties(KafkaTestUtils.consumerProps(groupId, "false", broker))
        properties[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        properties[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        properties[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = ByteArrayDeserializer::class.java
        return KafkaConsumer(properties)
    }

    private fun brokerProperties(source: Map<String, Any>): MutableMap<String, Any> =
        HashMap<String, Any>().also { copy -> source.forEach { (key, value) -> copy[key] = value } }

    private fun frame(schemaId: Int, json: String): ByteArray {
        val body = json.toByteArray()
        val framed = ByteArray(5 + body.size)
        framed[0] = 0
        ByteBuffer.wrap(framed, 1, 4).putInt(schemaId)
        body.copyInto(framed, 5)
        return framed
    }

    private fun <V> awaitRecord(consumer: KafkaConsumer<String, V>): ConsumerRecord<String, V> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val record = consumer.poll(Duration.ofMillis(200)).firstOrNull()
            if (record != null) return record
        }
        throw AssertionError("Timed out waiting for a Kafka record.")
    }

    private fun committedOffset(
        admin: AdminClient,
        groupId: String,
        topicPartition: TopicPartition
    ): Long? = admin.listConsumerGroupOffsets(groupId)
        .partitionsToOffsetAndMetadata()
        .get(5, TimeUnit.SECONDS)[topicPartition]
        ?.offset()

    private fun awaitCommittedOffset(
        admin: AdminClient,
        groupId: String,
        topicPartition: TopicPartition,
        expectedOffset: Long
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (committedOffset(admin, groupId, topicPartition) == expectedOffset) return
            Thread.sleep(25)
        }
        assertEquals(expectedOffset, committedOffset(admin, groupId, topicPartition))
    }

    @Configuration(proxyBeanMethods = false)
    class TestConfiguration
}
