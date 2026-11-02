export interface AllocationForm {
  orderId: string;
  customer: string;
  productQuery: string;
  quantity: string;
  priority: string;
  placedDate: string;
  requiredDeliveryDate: string;
  asOfDate: string;
  allowEquivalent: boolean;
}

export interface ProductAllocationRequest {
  requestId: string;
  orderId: string;
  customer: string;
  productQuery: string;
  quantity: number;
  priority: number;
  placedDate: string;
  requiredDeliveryDate: string;
  asOfDate: string;
  allowEquivalent: boolean;
}

export interface ProductCandidate {
  sku: string;
  title: string;
  similarity: number;
}

export type AllocationProcessingOutcome = "PROCESSED" | "SKIPPED_ORDER_EXPIRED";

export interface AllocationSource {
  sourceId: string;
  sourceType: "INVENTORY" | "INBOUND";
  quantity: number;
  sourceDate: string;
  warehouseLocation: string | null;
  shipmentId: string | null;
  shipmentLineId: string | null;
}

export interface AllocationResult {
  status: "ALLOCATION_PROCESSED" | "TAVILY_LOOKUP_FAILED" | "EMBEDDING_FAILED" |
    "NO_PRODUCT_MATCH" | "AMBIGUOUS_PRODUCT_MATCH";
  message: string;
  selectedProduct: (ProductCandidate & { equivalent: boolean }) | null;
  candidates: ProductCandidate[];
  sources: { title: string; url: string }[];
  allocationRun: {
    runId: string;
    openBackorderCount: number;
    allocations: {
      orderId: string;
      orderLineId: string;
      sku: string;
      requestedQuantity: number;
      allocatedFromInventory: number;
      allocatedFromInbound: number;
      backorderedQuantity: number;
      status: string | null;
      processingOutcome: AllocationProcessingOutcome;
      sources: AllocationSource[];
    }[];
  } | null;
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

export function buildAllocationRequest(
  form: AllocationForm,
  requestId: string,
): ProductAllocationRequest {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)) {
    throw new Error("A valid request UUID is required.");
  }
  const orderId = form.orderId.trim();
  const customer = form.customer.trim();
  const productQuery = form.productQuery.trim();
  if (!orderId || !customer || !productQuery) {
    throw new Error("Order ID, customer, and product description are required.");
  }
  const placedDate = date(form.placedDate, "Placed date");
  const requiredDeliveryDate = date(form.requiredDeliveryDate, "Delivery date");
  if (requiredDeliveryDate < placedDate) {
    throw new Error("Delivery date cannot be before the placed date.");
  }
  return {
    requestId,
    orderId,
    customer,
    productQuery,
    quantity: positiveInteger(form.quantity, "Quantity"),
    priority: positiveInteger(form.priority, "Priority"),
    placedDate,
    requiredDeliveryDate,
    asOfDate: date(form.asOfDate, "As-of date"),
    allowEquivalent: form.allowEquivalent,
  };
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function text(value: unknown): value is string {
  return typeof value === "string";
}

function number(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function localDate(value: unknown): string | null {
  if (text(value)) return value;
  if (!Array.isArray(value) || value.length !== 3 || !value.every(number)) return null;
  const [year, month, day] = value;
  if (!Number.isInteger(year) || !Number.isInteger(month) || !Number.isInteger(day)) return null;
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  return `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

function candidate(value: unknown): value is ProductCandidate {
  return record(value) && text(value.sku) && text(value.title) && number(value.similarity);
}

function allocationSource(value: unknown): value is AllocationSource {
  if (!record(value) || !text(value.sourceId) ||
      (value.sourceType !== "INVENTORY" && value.sourceType !== "INBOUND") ||
      !number(value.quantity) ||
      (value.warehouseLocation !== null && !text(value.warehouseLocation)) ||
      (value.shipmentId !== null && !text(value.shipmentId)) ||
      (value.shipmentLineId !== null && !text(value.shipmentLineId))) {
    return false;
  }
  const normalizedDate = localDate(value.sourceDate);
  if (!normalizedDate) return false;
  value.sourceDate = normalizedDate;
  return true;
}

function allocationResult(value: unknown): value is AllocationResult {
  if (!record(value) || !text(value.status) || !text(value.message)) return false;
  if (!["ALLOCATION_PROCESSED", "TAVILY_LOOKUP_FAILED", "EMBEDDING_FAILED",
    "NO_PRODUCT_MATCH", "AMBIGUOUS_PRODUCT_MATCH"].includes(value.status)) return false;
  if (!("selectedProduct" in value) || !("allocationRun" in value)) return false;
  if (value.selectedProduct !== null &&
      (!candidate(value.selectedProduct) || !("equivalent" in value.selectedProduct) ||
       typeof value.selectedProduct.equivalent !== "boolean")) return false;
  if (!Array.isArray(value.candidates) || !value.candidates.every(candidate)) return false;
  if (!Array.isArray(value.sources) || !value.sources.every(
    (source: unknown) => record(source) && text(source.title) && text(source.url),
  )) return false;
  if (value.status === "ALLOCATION_PROCESSED" &&
      (value.selectedProduct === null || value.allocationRun === null)) return false;
  if (value.status !== "ALLOCATION_PROCESSED" && value.allocationRun !== null) return false;
  if (value.allocationRun !== null) {
    const run = value.allocationRun;
    if (!record(run) || !text(run.runId) || !number(run.openBackorderCount) ||
        !Array.isArray(run.allocations) || !run.allocations.every(
          (item: unknown) => record(item) && text(item.orderId) && text(item.orderLineId) && text(item.sku) &&
            ((item.processingOutcome === "PROCESSED" && text(item.status)) ||
             (item.processingOutcome === "SKIPPED_ORDER_EXPIRED" && item.status === null)) &&
            Array.isArray(item.sources) && item.sources.every(allocationSource) &&
            number(item.requestedQuantity) &&
            number(item.allocatedFromInventory) && number(item.allocatedFromInbound) &&
            number(item.backorderedQuantity),
        )) return false;
  }
  return true;
}

export function readAllocationResult(response: unknown): AllocationResult {
  if (allocationResult(response)) return response;
  throw new Error("The allocation API returned an invalid product allocation result.");
}

export function readAllocationError(response: unknown, status: number): string {
  if (record(response)) {
    if (text(response.error) && response.error.trim()) return response.error;
    if (text(response.message) && response.message.trim()) return response.message;
  }
  return `Allocation API returned HTTP ${status}.`;
}

export function safeSourceUrl(value: string): string | null {
  try {
    const url = new URL(value);
    return ["http:", "https:"].includes(url.protocol) ? url.href : null;
  } catch (error) {
    if (!(error instanceof TypeError)) throw error;
    return null;
  }
}
