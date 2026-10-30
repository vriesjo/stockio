package nl.stockio.product.domain.api

import nl.stockio.api.dto.ProductAllocationRequest
import nl.stockio.api.dto.ProductAllocationResponse
import nl.stockio.product.domain.api.generated.ProductAllocationApi
import nl.stockio.product.domain.service.ProductAllocationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class ProductAllocationController(
    private val productAllocationService: ProductAllocationService
) : ProductAllocationApi {

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun allocate(
        request: ProductAllocationRequest
    ): ResponseEntity<ProductAllocationResponse> =
        ResponseEntity.ok(productAllocationService.allocate(request))

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidRequest(exception: IllegalArgumentException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Invalid product allocation request."))
}
