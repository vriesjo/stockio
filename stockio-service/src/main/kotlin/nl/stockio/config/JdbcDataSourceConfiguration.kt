package nl.stockio.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.StringUtils
import javax.sql.DataSource

/**
 * Spring Boot skips [org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration]
 * when an R2DBC `ConnectionFactory` is present. JDBC, JPA, Flyway, and JobRunr still need
 * the Hikari pool, so this configuration creates it from `spring.datasource`.
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties::class)
class JdbcDataSourceConfiguration {

    @Bean
    @ConditionalOnMissingBean(DataSource::class)
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    fun dataSource(properties: DataSourceProperties): HikariDataSource {
        val dataSource = properties.initializeDataSourceBuilder()
            .type(HikariDataSource::class.java)
            .build()
        if (StringUtils.hasText(properties.name)) {
            dataSource.poolName = properties.name
        }
        return dataSource
    }
}
