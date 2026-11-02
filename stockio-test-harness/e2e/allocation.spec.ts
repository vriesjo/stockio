import { createServer } from "node:http";
import { expect, test } from "@playwright/test";
import type { ProductAllocationRequest } from "../../stockio-api/src/main/kotlin/nl/stockio/ui/lib/allocation";

test("submits the form through the Next proxy and preserves identity on retry", async ({ page }) => {
  const requests: ProductAllocationRequest[] = [];
  const backend = createServer(async (request, response) => {
    const chunks: Buffer[] = [];
    for await (const chunk of request) chunks.push(Buffer.from(chunk));
    expect(request.method).toBe("POST");
    expect(request.url).toBe("/api/products/allocate");
    expect(request.headers["content-type"]).toContain("application/json");
    const body: ProductAllocationRequest = JSON.parse(Buffer.concat(chunks).toString());
    requests.push(body);
    response.setHeader("Content-Type", "application/json");
    if (requests.length === 2) {
      response.end(JSON.stringify({
        status: "EMBEDDING_FAILED", message: "Ollama read timed out",
        selectedProduct: null, candidates: [], sources: [], allocationRun: null,
      }));
    } else if (requests.length === 3) {
      response.statusCode = 400;
      response.end(JSON.stringify({ error: "Order already exists as new demand." }));
    } else if (requests.length === 4) {
      response.end(JSON.stringify({ status: "ALLOCATION_PROCESSED" }));
    } else {
      response.end(JSON.stringify({
        status: "ALLOCATION_PROCESSED",
        message: "Allocation processed with a shortage.",
        selectedProduct: { sku: "MAKI-KOMKOMMER", title: "Maki Komkommer", similarity: 1, equivalent: false },
        candidates: [{ sku: "MAKI-KOMKOMMER", title: "Maki Komkommer", similarity: 1 }],
        sources: [{ title: "Product evidence", url: "https://example.test/product" }],
        allocationRun: {
          runId: "550e8400-e29b-41d4-a716-446655440000",
          openBackorderCount: 1,
          allocations: [{
            orderId: body.orderId, sku: "MAKI-KOMKOMMER", requestedQuantity: body.quantity,
            orderLineId: "ORDER-LINE-001",
            allocatedFromInventory: 30, allocatedFromInbound: 10, backorderedQuantity: 10,
            status: "PARTIALLY_BACKORDERED",
            processingOutcome: "PROCESSED",
            sources: [{
              sourceId: "SHIPMENT-LINE-001", sourceType: "INBOUND", quantity: 10,
              sourceDate: body.requiredDeliveryDate, warehouseLocation: "MAIN",
              shipmentId: "SHIPMENT-001", shipmentLineId: "SHIPMENT-LINE-001",
            }],
          }, {
            orderId: body.orderId, orderLineId: "ORDER-LINE-002", sku: "MAKI-KOMKOMMER",
            requestedQuantity: 5, allocatedFromInventory: 0, allocatedFromInbound: 0,
            backorderedQuantity: 0, status: null, processingOutcome: "SKIPPED_ORDER_EXPIRED",
            sources: [],
          }],
        },
      }));
    }
  });
  const backendUrl = new URL(process.env.STOCKIO_API_URL ?? "");
  if (backendUrl.hostname !== "127.0.0.1" || !backendUrl.port || ["8080", "8082"].includes(backendUrl.port)) {
    throw new Error("Run E2E tests with STOCKIO_API_URL pointing to a dedicated localhost mock port, not the real API.");
  }
  await new Promise<void>((resolve, reject) => {
    backend.once("error", reject);
    backend.listen(Number(backendUrl.port), "127.0.0.1", resolve);
  });
  try {
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Find the right product. Allocate with confidence." })).toBeVisible();
    await page.getByLabel("Order ID", { exact: true }).fill("ORDER-UI-001");
    await page.getByLabel("Customer", { exact: true }).fill("Example Customer");
    await page.getByRole("button", { name: "Allocate product", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Allocation processed", exact: true })).toBeVisible();
    await expect(page.getByRole("table")).toContainText("50");
    await expect(page.getByRole("table")).toContainText("PARTIALLY BACKORDERED");
    await expect(page.getByRole("table")).toContainText("ORDER-LINE-001");
    await expect(page.getByRole("table")).toContainText("ORDER-LINE-002");
    await expect(page.getByRole("table")).toContainText("Skipped: order expired");
    await expect(page.getByRole("table")).toContainText("SHIPMENT-001 / Line: SHIPMENT-LINE-001");
    await expect(page.getByRole("table").getByRole("row")).toHaveCount(3);
    await expect(page.getByRole("link", { name: "Product evidence" })).toHaveAttribute("href", "https://example.test/product");
    await page.getByText("Product candidates (1)", { exact: true }).click();
    await expect(page.getByText("Score 1.000", { exact: true })).toBeVisible();
    await page.getByText("Raw API response", { exact: true }).click();
    await expect(page.getByText("Raw API response", { exact: true }).locator("..")).toContainText('"status": "ALLOCATION_PROCESSED"');
    expect(requests[0]).toMatchObject({
      orderId: "ORDER-UI-001", customer: "Example Customer", productQuery: "Maki Komkommer",
      quantity: 50, priority: 1, allowEquivalent: true,
    });
    expect(requests[0].requestId).toMatch(/^[0-9a-f-]{36}$/);
    expect(requests[0].placedDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);

    await page.getByRole("button", { name: "Allocate product", exact: true }).click();
    await expect(page.getByRole("heading", { name: "No allocation was made", exact: true })).toBeVisible();
    await expect(page.getByRole("region", { name: "Allocation result" })).toContainText("Ollama read timed out");
    await expect(page.getByRole("table")).toHaveCount(0);
    expect(requests[1].requestId).toBe(requests[0].requestId);

    await page.getByLabel("Quantity", { exact: true }).fill("51");
    await page.getByRole("button", { name: "Allocate product", exact: true }).click();
    await expect(page.getByRole("alert", { name: "Request could not be completed" })).toContainText("Order already exists as new demand.");
    expect(requests[2].requestId).not.toBe(requests[0].requestId);
    expect(requests[2].quantity).toBe(51);
    await page.getByRole("button", { name: "Allocate product", exact: true }).click();
    await expect(page.getByRole("alert", { name: "Request could not be completed" })).toContainText("invalid product allocation result");
    expect(requests[3].requestId).toBe(requests[2].requestId);
    expect(requests).toHaveLength(4);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  } finally {
    await new Promise<void>((resolve, reject) => {
      backend.close((error) => error ? reject(error) : resolve());
      backend.closeAllConnections();
    });
  }
});
