package nl.stockio.allocation.domain.job.listener

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.allocation.domain.service.AllocationRequestLimits
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
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

    @Test
    fun `does not commit offset until synchronous listener work completes`() {
        val groupId = "stockio-allocation-offset-test"
        val topicPartition = TopicPartition("stockio.allocation.offset-test", 0)
        val submitted = CountDownLatch(1)
        val completion = CompletableFuture<Void>()
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val request = AllocationRequest(
            java.util.UUID.randomUUID(),
            LocalDate.now(),
            AllocationRunType.NEW_DEMAND
        )
        val workQueue = Mockito.mock(AllocationKafkaWorkQueue::class.java)
        Mockito.`when`(workQueue.submit(request)).thenAnswer {
            submitted.countDown()
            completion
        }
        val listener = AllocationKafkaListener(
            mapper,
            Mockito.mock(AllocationRequestLimits::class.java),
            workQueue
        )
        val consumerProperties = KafkaTestUtils.consumerProps(groupId, "false", broker).apply {
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        }
        val consumerFactory = DefaultKafkaConsumerFactory<String, String>(consumerProperties)
        val containerProperties = ContainerProperties(topicPartition.topic()).apply {
            this.groupId = groupId
            ackMode = ContainerProperties.AckMode.RECORD
        }
        val container = KafkaMessageListenerContainer<String, String>(consumerFactory, containerProperties)
        container.setupMessageListener(MessageListener<String, String> { record -> listener.receive(record.value()) })
        val producerProperties = KafkaTestUtils.producerProps(broker).apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
        }
        val producerFactory = DefaultKafkaProducerFactory<String, String>(producerProperties)
        val template = KafkaTemplate<String, String>(producerFactory)
        val admin = AdminClient.create(
            mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to broker.brokersAsString)
        )

        try {
            container.start()
            ContainerTestUtils.waitForAssignment(container, 1)
            template.send(topicPartition.topic(), mapper.writeValueAsString(request)).get(5, TimeUnit.SECONDS)

            assertTrue(submitted.await(5, TimeUnit.SECONDS))
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
    fun `routes invalid records to the dead-letter topic`() {
        val sourceTopic = "stockio.allocation.invalid-test"
        val deadLetterTopic = "$sourceTopic.DLT"
        val groupId = "stockio-allocation-invalid-test"
        val topicPartition = TopicPartition(sourceTopic, 0)
        val workQueue = Mockito.mock(AllocationKafkaWorkQueue::class.java)
        val listener = AllocationKafkaListener(
            jacksonObjectMapper().findAndRegisterModules(),
            Mockito.mock(AllocationRequestLimits::class.java),
            workQueue
        )
        val consumerProperties = KafkaTestUtils.consumerProps(groupId, "false", broker).apply {
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        }
        val containerProperties = ContainerProperties(sourceTopic).apply {
            this.groupId = groupId
            ackMode = ContainerProperties.AckMode.RECORD
        }
        val container = KafkaMessageListenerContainer<String, String>(
            DefaultKafkaConsumerFactory(consumerProperties),
            containerProperties
        )
        container.setupMessageListener(MessageListener<String, String> { record -> listener.receive(record.value()) })
        val producerProperties = KafkaTestUtils.producerProps(broker).apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
        }
        val producerFactory = DefaultKafkaProducerFactory<String, String>(producerProperties)
        val template = KafkaTemplate<String, String>(producerFactory)
        val recoverer = DeadLetterPublishingRecoverer(template) { record, _ ->
            TopicPartition(deadLetterTopic, record.partition())
        }.also { it.setFailIfSendResultIsError(true) }
        val errorHandler = DefaultErrorHandler(recoverer, FixedBackOff(0, 0)).also {
            it.addNotRetryableExceptions(IllegalArgumentException::class.java)
        }
        container.setCommonErrorHandler(errorHandler)
        val dltProperties = KafkaTestUtils.consumerProps("stockio-allocation-dlt-reader", "false", broker).apply {
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        val dltConsumer = KafkaConsumer<String, String>(dltProperties)

        try {
            container.start()
            ContainerTestUtils.waitForAssignment(container, 1)
            dltConsumer.subscribe(listOf(deadLetterTopic))
            template.send(sourceTopic, "{").get(5, TimeUnit.SECONDS)

            var deadLetterRecord = dltConsumer.poll(Duration.ofSeconds(5)).firstOrNull()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (deadLetterRecord == null && System.nanoTime() < deadline) {
                deadLetterRecord = dltConsumer.poll(Duration.ofMillis(100)).firstOrNull()
            }
            assertEquals("{", deadLetterRecord?.value())
            Mockito.verifyNoInteractions(workQueue)
        } finally {
            container.stop()
            dltConsumer.close()
            producerFactory.destroy()
        }
    }

    @Test
    fun `retries failed handoff then routes exhausted record to dead-letter topic`() {
        val sourceTopic = "stockio.allocation.retry-test"
        val deadLetterTopic = "$sourceTopic.DLT"
        val groupId = "stockio-allocation-retry-test"
        val request = AllocationRequest(
            java.util.UUID.randomUUID(),
            LocalDate.now(),
            AllocationRunType.NEW_DEMAND
        )
        val workQueue = Mockito.mock(AllocationKafkaWorkQueue::class.java)
        Mockito.`when`(workQueue.submit(request)).thenReturn(
            CompletableFuture.failedFuture(IllegalStateException("Durable handoff failed."))
        )
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val listener = AllocationKafkaListener(
            mapper,
            Mockito.mock(AllocationRequestLimits::class.java),
            workQueue
        )
        val groupConsumerProperties = KafkaTestUtils.consumerProps(groupId, "false", broker).apply {
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        }
        val containerProperties = ContainerProperties(sourceTopic).apply {
            this.groupId = groupId
            ackMode = ContainerProperties.AckMode.RECORD
        }
        val container = KafkaMessageListenerContainer<String, String>(
            DefaultKafkaConsumerFactory(groupConsumerProperties),
            containerProperties
        )
        container.setupMessageListener(MessageListener<String, String> { record -> listener.receive(record.value()) })
        val producerProperties = KafkaTestUtils.producerProps(broker).apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
        }
        val producerFactory = DefaultKafkaProducerFactory<String, String>(producerProperties)
        val template = KafkaTemplate<String, String>(producerFactory)
        val recoverer = DeadLetterPublishingRecoverer(template) { record, _ ->
            TopicPartition(deadLetterTopic, record.partition())
        }.also { it.setFailIfSendResultIsError(true) }
        container.setCommonErrorHandler(
            DefaultErrorHandler(recoverer, FixedBackOff(0, 1))
                .also { it.addNotRetryableExceptions(IllegalArgumentException::class.java) }
        )
        val dltConsumer = KafkaConsumer<String, String>(
            KafkaTestUtils.consumerProps("stockio-allocation-retry-dlt-reader", "false", broker).apply {
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            }
        )

        try {
            container.start()
            ContainerTestUtils.waitForAssignment(container, 1)
            dltConsumer.subscribe(listOf(deadLetterTopic))
            template.send(sourceTopic, mapper.writeValueAsString(request)).get(5, TimeUnit.SECONDS)

            var deadLetterRecord = dltConsumer.poll(Duration.ofSeconds(5)).firstOrNull()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (deadLetterRecord == null && System.nanoTime() < deadline) {
                deadLetterRecord = dltConsumer.poll(Duration.ofMillis(100)).firstOrNull()
            }
            assertEquals(mapper.writeValueAsString(request), deadLetterRecord?.value())
            Mockito.verify(workQueue, Mockito.times(2)).submit(request)
        } finally {
            container.stop()
            dltConsumer.close()
            producerFactory.destroy()
        }
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
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (committedOffset(admin, groupId, topicPartition) == expectedOffset) return
            Thread.sleep(25)
        }
        assertEquals(expectedOffset, committedOffset(admin, groupId, topicPartition))
    }

    @Configuration(proxyBeanMethods = false)
    class TestConfiguration
}
