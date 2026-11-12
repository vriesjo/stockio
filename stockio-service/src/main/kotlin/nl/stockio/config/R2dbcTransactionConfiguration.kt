package nl.stockio.config

import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

@Configuration
class R2dbcTransactionConfiguration {
    @Bean
    fun transactionalOperator(transactionManager: ReactiveTransactionManager): TransactionalOperator =
        TransactionalOperator.create(transactionManager)

    /**
     * `@Transactional` looks up `TransactionManager` by type. JPA and R2DBC each
     * register one, so the JDBC manager stays primary for existing services.
     * SKU allocation injects [ReactiveTransactionManager] directly.
     */
    @Bean
    fun jdbcTransactionManagerPrimary(): BeanFactoryPostProcessor = BeanFactoryPostProcessor { beanFactory ->
        if (beanFactory is BeanDefinitionRegistry && beanFactory.containsBeanDefinition("transactionManager")) {
            beanFactory.getBeanDefinition("transactionManager").isPrimary = true
        }
    }
}
