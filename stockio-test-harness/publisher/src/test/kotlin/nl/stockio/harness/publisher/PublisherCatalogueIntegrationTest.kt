package nl.stockio.harness.publisher

import com.zaxxer.hikari.HikariDataSource
import java.time.Clock
import nl.stockio.api.dto.AllocationRequest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class PublisherCatalogueIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Test
    fun `publishes catalogue SKUs using a read-only connection without service migrations`() {
        val admin = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
        admin.execute("create table products (sku varchar(255) primary key, status varchar(32))")
        admin.execute("insert into products values ('CATALOGUE-SKU', 'INACTIVE')")
        admin.execute("create user harness_reader password 'test-reader-password'")
        admin.execute("grant select on products to harness_reader")
        val rabbitTemplate = Mockito.mock(RabbitTemplate::class.java)

        HikariDataSource().use { datasource ->
            datasource.jdbcUrl = postgres.jdbcUrl
            datasource.username = "harness_reader"
            datasource.password = "test-reader-password"
            datasource.isReadOnly = true
            datasource.maximumPoolSize = 1
            val publisher = AllocationRequestPublisher(
                rabbitTemplate, JdbcTemplate(datasource), Clock.systemUTC(), "allocation.exchange", "allocation.request"
            )

            publisher.publishSampleRequest()

            val requestCaptor = ArgumentCaptor.forClass(AllocationRequest::class.java)
            Mockito.verify(rabbitTemplate).convertAndSend(
                ArgumentMatchers.eq("allocation.exchange"),
                ArgumentMatchers.eq("allocation.request"),
                requestCaptor.capture()
            )
            val request = requestCaptor.value
            Assertions.assertEquals(setOf("CATALOGUE-SKU"), request.demandOrders.flatMap { it.lines }.map { it.sku }.toSet())
            Assertions.assertEquals(setOf("CATALOGUE-SKU"), request.inboundShipments.flatMap { it.lines }.map { it.sku }.toSet())
        }
    }
}
