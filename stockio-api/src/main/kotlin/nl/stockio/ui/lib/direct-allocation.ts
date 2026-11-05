export interface DirectAllocationForm {
  orderId: string;
  customer: string;
  sku: string;
  asyncMode: "true" | "false";
  quantity: string;
  priority: string;
  placedDate: string;
  requiredDeliveryDate: string;
  asOfDate: string;
}

export interface AllocationRequest {
  requestId: string;
  asOfDate: string;
  runType: "NEW_DEMAND" | "BACKORDER_FULFILLMENT";
  demandOrders: DemandOrder[];
  inboundShipments: InboundShipment[];
}

export interface DemandOrder {
  orderId: string;
  customer: string;
  priority: number;
  placedDate: string;
  lines: DemandOrderLine[];
}

export interface DemandOrderLine {
  orderLineId: string;
  sku: string;
  requestedQuantity: number;
  requiredDeliveryDate: string;
}

export interface InboundShipment {
  shipmentId: string;
  lines: InboundShipmentLine[];
}

export interface InboundShipmentLine {
  shipmentLineId: string;
  sku: string;
  expectedQuantity: number;
  expectedArrivalDate: string;
  warehouseCode: string;
}

export interface AllocationJobAccepted {
  jobId: string;
  requestId: string;
}

function positiveInteger(value: string, label: string): number {
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(Number(value)) ||
      Number(value) < 1 || Number(value) > 2_147_483_647) {
    throw new Error(`${label} must be a whole number between 1 and 2,147,483,647.`);
  }
  return Number(value);
}

function date(value: string, label: string): string {
  const parsed = new Date(`${value}T00:00:00Z`);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(parsed.getTime()) ||
      parsed.toISOString().slice(0, 10) !== value) {
    throw new Error(`${label} must be a valid date.`);
  }
  return value;
}

function lineId(orderId: string): string {
  return `${orderId}-line-1`;
}

export function buildDirectAllocationRequest(
  form: DirectAllocationForm,
  requestId: string,
): AllocationRequest {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)) {
    throw new Error("A valid request UUID is required.");
  }
  const orderId = form.orderId.trim();
  const customer = form.customer.trim();
  const sku = form.sku.trim();
  if (!orderId || !customer || !sku) {
    throw new Error("Order ID, customer, and SKU are required.");
  }
  const placedDate = date(form.placedDate, "Placed date");
  const requiredDeliveryDate = date(form.requiredDeliveryDate, "Required delivery date");
  if (requiredDeliveryDate < placedDate) {
    throw new Error("Required delivery date cannot be before the placed date.");
  }
  return {
    requestId,
    asOfDate: date(form.asOfDate, "As-of date"),
    runType: "NEW_DEMAND",
    demandOrders: [{
      orderId,
      customer,
      priority: positiveInteger(form.priority, "Priority"),
      placedDate,
      lines: [{
        orderLineId: lineId(orderId),
        sku,
        requestedQuantity: positiveInteger(form.quantity, "Quantity"),
        requiredDeliveryDate,
      }],
    }],
    inboundShipments: [],
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isText(value: unknown): value is string {
  return typeof value === "string";
}

function isUuid(value: unknown): value is string {
  return isText(value) && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

export function readAllocationJobAccepted(response: unknown): AllocationJobAccepted {
  if (!isRecord(response) || !isUuid(response.jobId) || !isUuid(response.requestId)) {
    throw new Error("The allocation jobs API returned an invalid acceptance payload.");
  }
  return {
    jobId: response.jobId,
    requestId: response.requestId,
  };
}

export function readAllocationJobError(response: unknown, status: number): string {
  if (isRecord(response)) {
    if (isText(response.error) && response.error.trim()) return response.error;
    if (isText(response.message) && response.message.trim()) return response.message;
  }
  return `Allocation jobs API returned HTTP ${status}.`;
}
