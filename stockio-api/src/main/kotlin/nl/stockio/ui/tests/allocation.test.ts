import assert from "node:assert/strict";
import test from "node:test";
import {
  buildAllocationRequest,
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
      sku: "MAKI-KOMKOMMER",
      requestedQuantity: 50,
      allocatedFromInventory: 30,
      allocatedFromInbound: 10,
      backorderedQuantity: 10,
      status: "PARTIALLY_BACKORDERED",
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

test("reads JSON embedded in MCP text and preserves actual shortage quantities", () => {
  const result = readAllocationResult({
    isError: false,
    content: [{ type: "text", text: JSON.stringify(allocation) }],
  });
  assert.deepEqual(result, allocation);
  assert.equal(result.allocationRun?.allocations[0].backorderedQuantity, 10);
});

test("supports MCP structured content and domain non-allocation statuses", () => {
  const noMatch = { ...allocation, status: "NO_PRODUCT_MATCH", selectedProduct: null, allocationRun: null };
  assert.deepEqual(readAllocationResult({ structuredContent: noMatch }), noMatch);
});

test("treats MCP isError as failure even when a successful payload is also present", () => {
  assert.throws(() => readAllocationResult({
    isError: true,
    structuredContent: allocation,
    content: [{ type: "text", text: "Ollama read timed out" }],
  }), /Ollama read timed out/);
});

test("rejects malformed or incomplete MCP results rather than showing success", () => {
  for (const response of [
    null, {}, { content: [{ type: "text", text: "not JSON" }] },
    { structuredContent: { status: "ALLOCATION_PROCESSED" } },
    { structuredContent: { ...allocation, allocationRun: { allocations: "invalid" } } },
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
