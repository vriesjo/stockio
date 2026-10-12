package nl.stockio.logistics.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ProductRepository : JpaRepository<ProductEntity, UUID> {
    @Query("select product.sku from ProductEntity product where lower(product.title) = lower(:title)")
    fun findSkuByTitle(@Param("title") title: String): String?
}
