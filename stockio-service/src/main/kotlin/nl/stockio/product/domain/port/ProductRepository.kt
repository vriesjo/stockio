package nl.stockio.product.domain.port

import nl.stockio.product.infra.ProductEntity

interface ProductRepository {

    fun findSkuByTitle(title: String): String?
    fun findAllByStatus(status: String): List<ProductEntity>
}
