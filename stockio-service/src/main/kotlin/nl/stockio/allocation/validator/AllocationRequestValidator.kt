package nl.stockio.allocation.validator

import jakarta.validation.Validator
import nl.stockio.allocation.domain.bean.AllocationRequest
import nl.stockio.allocation.domain.bean.AllocationRunType
import nl.stockio.allocation.infra.AllocationRecordRepository
import org.springframework.stereotype.Component

@Component
class AllocationRequestValidator(
    private val validator: Validator,
    private val recordRepository: AllocationRecordRepository,
) {

    fun validate(request: AllocationRequest) {
        when (request.runType) {
            AllocationRunType.NEW_DEMAND -> validateAllocationRequest(request)
            AllocationRunType.BACKORDER_FULFILLMENT -> validateBackorderAllocationRequest(request)
            else -> throw AllocationRequestException("Unsupported request type: ${request::class.simpleName}")
        }
    }

    private fun validateAllocationRequest(request: AllocationRequest) {
        requireAndThrow(request.runType == AllocationRunType.NEW_DEMAND) {
            AllocationRequestException("Run type must be of type: NEW_DEMAND")
        }
        validateBean(request)
        requireAndThrow(request.demandOrders.all { it.placedDate <= it.requiredDeliveryDate }) {
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
        val previouslyProcessedOrderIds = recordRepository.findAllByOrderIdIn(orderIds)
            .map { it.orderId }
            .distinct()
        requireAndThrow(previouslyProcessedOrderIds.isEmpty()) {
            AllocationRequestException(
                "Orders have already been processed: ${previouslyProcessedOrderIds.joinToString()}. " +
                    "Fulfill or resolve existing backorders instead of submitting those orders again."
            )
        }
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
