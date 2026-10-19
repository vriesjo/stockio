package nl.stockio.product.infra

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "products")
class ProductEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 64)
    var sku: String = "",
    @Column(nullable = false, length = 255)
    var title: String = "",
    @Column
    var description: String? = null,
    @Column(nullable = false, length = 20)
    var status: String = "ACTIVE",
    @Column(nullable = false, length = 20)
    var category: String = "H"
)
