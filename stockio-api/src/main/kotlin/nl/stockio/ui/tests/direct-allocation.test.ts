import assert from "node:assert/strict";
import test from "node:test";
import {
  buildDirectAllocationRequest,
  readAllocationJobAccepted,
  readAllocationJobError,
  type DirectAllocationForm,
} from "../lib/direct-allocation.ts";

const requestId = "550e8400-e29b-41d4-a716-446655440000";
const form: DirectAllocationForm = {
  orderId: " ORDER-API-001 ",
  customer: "Example Customer",
  sku: " MAKI-KOMKOMMER ",
  asyncMode: "true",
  quantity: "50",
  priority: "1",
  placedDate: "2026-10-19",
  requiredDeliveryDate: "2026-10-21",
  asOfDate: "2026-10-19",
};

test("maps form values to AllocationRequest using NEW_DEMAND and one demand line", () => {
  assert.deepEqual(buildDirectAllocationRequest(form, requestId), {
    requestId,
    asOfDate: "2026-10-19",
    runType: "NEW_DEMAND",
    demandOrders: [{
      orderId: "ORDER-API-001",
      customer: "Example Customer",
      priority: 1,
      placedDate: "2026-10-19",
      lines: [{
        orderLineId: "ORDER-API-001-line-1",
        sku: "MAKI-KOMKOMMER",
        requestedQuantity: 50,
        requiredDeliveryDate: "2026-10-21",
      }],
    }],
    inboundShipments: [],
  });
});

test("rejects missing metadata, invalid integers and invalid date ranges", () => {
  for (const patch of [
    { customer: " " }, { sku: " " }, { orderId: "" },
    { quantity: "0" }, { quantity: "1.5" }, { quantity: "2147483648" },
    { priority: "-1" }, { requiredDeliveryDate: "2026-10-18" },
    { placedDate: "2026-02-30" }, { asOfDate: "" },
  ]) {
    assert.throws(() => buildDirectAllocationRequest({ ...form, ...patch }, requestId));
  }
});

test("keeps user text as data rather than treating it as HTML", () => {
  const sku = "<script>alert(1)</script>";
  assert.equal(buildDirectAllocationRequest({ ...form, sku }, requestId).demandOrders[0].lines[0].sku, sku);
});

test("reads accepted response shape and rejects malformed payloads", () => {
  const accepted = {
    jobId: "7debf989-feb2-4c13-b91c-f177b5a3d8ab",
    requestId,
  };
  assert.deepEqual(readAllocationJobAccepted(accepted), accepted);
  for (const body of [null, {}, { jobId: "not-a-uuid", requestId }, { jobId: accepted.jobId }]) {
    assert.throws(() => readAllocationJobAccepted(body));
  }
});

test("reads service/proxy error messages with HTTP fallback", () => {
  assert.equal(readAllocationJobError({ error: "asOfDate must match current business date." }, 400), "asOfDate must match current business date.");
  assert.equal(readAllocationJobError({ message: "Cannot reach Stockio." }, 502), "Cannot reach Stockio.");
  for (const body of [null, {}, "Bad gateway", { error: "", message: "" }]) {
    assert.equal(readAllocationJobError(body, 500), "Allocation jobs API returned HTTP 500.");
  }
});
