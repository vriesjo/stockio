import assert from "node:assert/strict";
import test from "node:test";
import {
  buildAllocationRequest,
  readAllocationError,
  readAllocationResult,
  safeSourceUrl,
  type AllocationForm,
} from "../lib/allocation.ts";

const requestId = "550e8400-e29b-41d4-a716-446655440000";
const form: AllocationForm = {
  orderId: " ORDER-API-001 ",
  customer: "Example Customer",
  productQuery: "Maki Komkommer",
  quantity: "50",
  priority: "1",
  placedDate: "2026-10-19",
  requiredDeliveryDate: "2026-10-21",
  asOfDate: "2026-10-19",
  allowEquivalent: false,
};
const allocation = {
  status: "ALLOCATION_PROCESSED",
  message: "Allocation processed.",
  selectedProduct: { sku: "MAKI-KOMKOMMER", title: "Maki Komkommer", similarity: 1, equivalent: false },
  candidates: [],
  sources: [],
  allocationRun: {
    runId: requestId,
    openBackorderCount: 1,
    allocations: [{
      orderId: "ORDER-API-001",
      orderLineId: "ORDER-LINE-001",
      sku: "MAKI-KOMKOMMER",
      requestedQuantity: 50,
      allocatedFromInventory: 30,
      allocatedFromInbound: 10,
      backorderedQuantity: 10,
      status: "PARTIALLY_BACKORDERED",
      processingOutcome: "PROCESSED",
      sources: [{
        sourceId: "SHIPMENT-LINE-001",
        sourceType: "INBOUND",
        quantity: 10,
        sourceDate: "2026-10-21",
        warehouseLocation: "MAIN",
        shipmentId: "SHIPMENT-001",
        shipmentLineId: "SHIPMENT-LINE-001",
      }],
    }],
  },
};

test("maps form values to the exact flat Kotlin request shape", () => {
  assert.deepEqual(buildAllocationRequest(form, requestId), {
    requestId,
    orderId: "ORDER-API-001",
    customer: "Example Customer",
    productQuery: "Maki Komkommer",
    quantity: 50,
    priority: 1,
    placedDate: "2026-10-19",
    requiredDeliveryDate: "2026-10-21",
    asOfDate: "2026-10-19",
    allowEquivalent: false,
  });
});

test("rejects missing metadata, invalid integers and invalid date ranges", () => {
  for (const patch of [
    { customer: " " }, { productQuery: " " }, { orderId: "" },
    { quantity: "0" }, { quantity: "1.5" }, { quantity: "2147483648" },
    { priority: "-1" }, { requiredDeliveryDate: "2026-10-18" },
    { placedDate: "2026-02-30" }, { asOfDate: "" },
  ]) {
    assert.throws(() => buildAllocationRequest({ ...form, ...patch }, requestId));
  }
});

test("keeps user text as data rather than treating it as HTML", () => {
  const productQuery = '<img src=x onerror="alert(1)">';
  assert.equal(buildAllocationRequest({ ...form, productQuery }, requestId).productQuery, productQuery);
});

test("reads plain REST JSON and preserves actual shortage quantities", () => {
  const result = readAllocationResult(allocation);
  assert.deepEqual(result, allocation);
  assert.equal(result.allocationRun?.allocations[0].backorderedQuantity, 10);
});

test("preserves all domain non-allocation statuses and optional evidence", () => {
  for (const status of [
    "NO_PRODUCT_MATCH", "AMBIGUOUS_PRODUCT_MATCH", "EMBEDDING_FAILED", "TAVILY_LOOKUP_FAILED",
  ]) {
    const noMatch = {
      ...allocation, status, selectedProduct: null, allocationRun: null,
      candidates: [{ sku: "MAKI-ZALM", title: "Maki Zalm", similarity: 0.6 }],
      sources: [{ title: "Product evidence", url: "https://example.test/product" }],
    };
    assert.deepEqual(readAllocationResult(noMatch), noMatch);
  }
});

test("normalizes source dates when the service serializes LocalDate arrays", () => {
  const arrayDatePayload = {
    ...allocation,
    allocationRun: {
      ...allocation.allocationRun,
      allocations: [{
        ...allocation.allocationRun.allocations[0],
        sources: [{
          ...allocation.allocationRun.allocations[0].sources[0],
          sourceDate: [2026, 10, 21],
        }],
      }],
    },
  };
  const result = readAllocationResult(arrayDatePayload);
  assert.equal(result.allocationRun?.allocations[0].sources[0].sourceDate, "2026-10-21");
});

test("preserves repeated order and SKU lines and a skipped expiry outcome", () => {
  const processed = allocation.allocationRun.allocations[0];
  const multiLine = {
    ...allocation,
    allocationRun: {
      ...allocation.allocationRun,
      allocations: [
        processed,
        {
          ...processed,
          orderLineId: "ORDER-LINE-002",
          status: null,
          processingOutcome: "SKIPPED_ORDER_EXPIRED",
          allocatedFromInventory: 0,
          allocatedFromInbound: 0,
          backorderedQuantity: 0,
          sources: [],
        },
      ],
    },
  };
  const result = readAllocationResult(multiLine);
  assert.deepEqual(result, multiLine);
  assert.equal(result.allocationRun?.allocations[0].sources[0].shipmentLineId, "SHIPMENT-LINE-001");
  assert.equal(result.allocationRun?.allocations[1].status, null);
});

test("rejects missing line identity, invalid outcomes and inconsistent skipped status", () => {
  for (const patch of [
    { orderLineId: undefined }, { processingOutcome: "UNKNOWN" },
    { status: null }, { processingOutcome: "SKIPPED_ORDER_EXPIRED" },
    { sources: [{ sourceType: "INBOUND", shipmentId: "SHIPMENT-001" }] },
  ]) {
    assert.throws(() => readAllocationResult({
      ...allocation,
      allocationRun: {
        ...allocation.allocationRun,
        allocations: [{ ...allocation.allocationRun.allocations[0], ...patch }],
      },
    }));
  }
});

test("reads service validation and proxy error messages with an HTTP fallback", () => {
  assert.equal(readAllocationError({ error: "Quantity must be positive." }, 400), "Quantity must be positive.");
  assert.equal(readAllocationError({ message: "The Stockio API timed out." }, 504), "The Stockio API timed out.");
  for (const body of [null, {}, "Bad gateway", { error: "", message: "" }]) {
    assert.equal(readAllocationError(body, 500), "Allocation API returned HTTP 500.");
  }
});

test("rejects MCP envelopes and malformed or inconsistent REST results", () => {
  for (const response of [
    null, {}, { content: [{ type: "text", text: JSON.stringify(allocation) }] },
    { structuredContent: allocation },
    { status: "ALLOCATION_PROCESSED" },
    { ...allocation, allocationRun: { allocations: "invalid" } },
    { ...allocation, allocationRun: null },
    { ...allocation, selectedProduct: null },
    { ...allocation, selectedProduct: undefined },
    { ...allocation, allocationRun: undefined },
    { ...allocation, status: "UNKNOWN" },
    { ...allocation, status: "NO_PRODUCT_MATCH" },
  ]) {
    assert.throws(() => readAllocationResult(response));
  }
});

test("only permits HTTP(S) evidence links", () => {
  assert.equal(safeSourceUrl("https://example.test/product"), "https://example.test/product");
  assert.equal(safeSourceUrl("javascript:alert(1)"), null);
  assert.equal(safeSourceUrl("data:text/html,example"), null);
  assert.equal(safeSourceUrl("/relative"), null);
});
