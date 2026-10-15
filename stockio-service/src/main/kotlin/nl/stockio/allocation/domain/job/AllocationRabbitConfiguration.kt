package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.annotation.EnableRabbit
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@EnableRabbit
@EnableScheduling
@Configuration
class AllocationRabbitConfiguration {

    @Bean
    fun allocationRequestExchange(
        @Value("\${stockio.allocation.rabbitmq.exchange}") exchange: String
    ): TopicExchange = TopicExchange(exchange, true, false)

    @Bean
    fun allocationRequestQueue(
        @Value("\${stockio.allocation.rabbitmq.request-queue}") queue: String,
        @Value("\${stockio.allocation.rabbitmq.dead-letter-exchange}") deadLetterExchange: String,
        @Value("\${stockio.allocation.rabbitmq.dead-letter-routing-key}") deadLetterRoutingKey: String
    ): Queue = QueueBuilder.durable(queue)
        .deadLetterExchange(deadLetterExchange)
        .deadLetterRoutingKey(deadLetterRoutingKey)
        .build()

    @Bean
    fun allocationRequestBinding(
        allocationRequestQueue: Queue,
        allocationRequestExchange: TopicExchange,
        @Value("\${stockio.allocation.rabbitmq.routing-key}") routingKey: String
    ): Binding = BindingBuilder.bind(allocationRequestQueue).to(allocationRequestExchange).with(routingKey)

    @Bean
    fun allocationDeadLetterExchange(
        @Value("\${stockio.allocation.rabbitmq.dead-letter-exchange}") exchange: String
    ): DirectExchange = DirectExchange(exchange, true, false)

    @Bean
    fun allocationDeadLetterQueue(
        @Value("\${stockio.allocation.rabbitmq.dead-letter-queue}") queue: String
    ): Queue = QueueBuilder.durable(queue).build()

    @Bean
    fun allocationDeadLetterBinding(
        allocationDeadLetterQueue: Queue,
        allocationDeadLetterExchange: DirectExchange,
        @Value("\${stockio.allocation.rabbitmq.dead-letter-routing-key}") routingKey: String
    ): Binding = BindingBuilder.bind(allocationDeadLetterQueue).to(allocationDeadLetterExchange).with(routingKey)

    @Bean
    fun allocationJsonMessageConverter(objectMapper: ObjectMapper): MessageConverter =
        Jackson2JsonMessageConverter(objectMapper)
}
