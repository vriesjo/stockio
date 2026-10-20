package nl.stockio.product.domain.api

import jakarta.validation.Valid
import nl.stockio.product.domain.model.ProductAllocationRequest
import nl.stockio.product.domain.model.ProductAllocationResponse
import nl.stockio.product.domain.service.ProductAllocationService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/products")
class ProductAllocationController(
    private val productAllocationService: ProductAllocationService
) {

    @PostMapping("/allocate")
    fun allocate(
        @Valid @RequestBody request: ProductAllocationRequest
    ): ProductAllocationResponse =
        productAllocationService.allocate(request)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidRequest(exception: IllegalArgumentException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Invalid product allocation request."))
}
