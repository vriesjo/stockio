package nl.stockio.product.infra

import java.util.UUID
import nl.stockio.product.domain.port.ProductRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

@ConditionalOnProperty(prefix = "stockio.product.repository", name = ["type"], havingValue = "jpa", matchIfMissing = true)
interface ProductJpaRepository : ProductRepository, JpaRepository<ProductEntity, UUID> {
    override fun findAll(): List<ProductEntity>

    @Query("select product.sku from ProductEntity product where lower(product.title) = lower(:title)")
    override fun findSkuByTitle(@Param("title") title: String): String?

    override fun findAllByStatus(status: String): List<ProductEntity>
}
