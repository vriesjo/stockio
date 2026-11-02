package nl.stockio.harness.publisher

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZoneId
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class AllocationPublisherApplication {

    @Bean
    fun publisherClock(@Value("\${stockio.harness.zone:UTC}") zone: String): Clock =
        Clock.system(ZoneId.of(zone))

    @Bean
    fun allocationJsonMessageConverter(objectMapper: ObjectMapper): Jackson2JsonMessageConverter =
        Jackson2JsonMessageConverter(objectMapper)
}

fun main(args: Array<String>) {
    runApplication<AllocationPublisherApplication>(*args)
}
