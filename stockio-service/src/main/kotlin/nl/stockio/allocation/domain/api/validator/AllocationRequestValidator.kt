package nl.stockio.allocation.domain.api.validator

import jakarta.validation.Validator
import nl.stockio.allocation.domain.api.data.AllocationRequest
import nl.stockio.allocation.domain.api.data.AllocationRunType
import org.springframework.stereotype.Component

@Component
class AllocationRequestValidator(
    private val validator: Validator
) {

    fun validate(request: AllocationRequest) {
        when (request.runType) {
            AllocationRunType.NEW_DEMAND -> validateAllocationRequest(request)
            AllocationRunType.BACKORDER_FULFILLMENT -> validateBackorderAllocationRequest(request)
        }
    }

    private fun validateAllocationRequest(request: AllocationRequest) {
        requireAndThrow(request.runType == AllocationRunType.NEW_DEMAND) {
            AllocationRequestException("Run type must be of type: NEW_DEMAND")
        }
        validateBean(request)
        requireAndThrow(request.demandOrders.all { order ->
            order.lines.all { order.placedDate <= it.requiredDeliveryDate }
        }) {
            AllocationRequestException("An order's placed date must be on or before its required delivery date.")
        }
        val inboundShipments = request.inboundShipments
        validateBean(inboundShipments)
        requireAndThrow(inboundShipments.map { it.shipmentId }.distinct().size == inboundShipments.size) {
            AllocationRequestException("Inbound shipment IDs must be unique within a request.")
        }
        val orderIds = request.demandOrders.map { it.orderId }
        requireAndThrow(orderIds.distinct().size == orderIds.size) {
            AllocationRequestException("Demand order IDs must be unique within a request.")
        }
        val lineIds = request.demandOrders.flatMap { it.lines }.map { it.orderLineId }
        requireAndThrow(lineIds.distinct().size == lineIds.size) {
            AllocationRequestException("Demand order line IDs must be globally unique within a request.")
        }
        validateShipmentLineIds(request)
    }

    private fun validateBackorderAllocationRequest(request: AllocationRequest) {
        requireAndThrow(request.runType == AllocationRunType.BACKORDER_FULFILLMENT) {
            AllocationRequestException("Run type must be of type: BACKORDER_FULFILLMENT")
        }
        validateBean(request)

        val inboundShipments = request.inboundShipments
        validateBean(inboundShipments)
        requireAndThrow(inboundShipments.map { it.shipmentId }.distinct().size == inboundShipments.size) {
            AllocationRequestException("Inbound shipment IDs must be unique within a backorder request.")
        }
        validateShipmentLineIds(request)
    }

    private fun validateShipmentLineIds(request: AllocationRequest) {
        val ids = request.inboundShipments.flatMap { it.lines }.map { it.shipmentLineId }
        requireAndThrow(ids.distinct().size == ids.size) {
            AllocationRequestException("Inbound shipment line IDs must be globally unique within a request.")
        }
    }
    private fun validateBean(value: Any) {
        val violations: Map<String, String> = validator
            .validate(value)
            .associate { it.propertyPath.toString() to it.message }
        requireAndThrow(violations.isEmpty()) {
            AllocationRequestException("Validate of bean ${value::class.simpleName} failed", violations)
        }
    }

    private fun requireAndThrow(condition: Boolean, customException: () -> Throwable) {
        if (!condition) {
            throw customException()
        }
    }

}
