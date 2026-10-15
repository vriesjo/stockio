package nl.stockio.product.infra

import java.util.UUID
import nl.stockio.product.infra.ProductEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProductRepository : JpaRepository<ProductEntity, UUID> {
    @Query("select product.sku from ProductEntity product where lower(product.title) = lower(:title)")
    fun findSkuByTitle(@Param("title") title: String): String?
}
