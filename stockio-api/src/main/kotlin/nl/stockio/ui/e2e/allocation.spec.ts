import { createServer } from "node:http";
import { expect, test } from "@playwright/test";
import type { McpProductAllocationRequest } from "../lib/allocation";

test("submits the form through the Next proxy and preserves identity on retry", async ({ page }) => {
  const requests: McpProductAllocationRequest[] = [];
  const backend = createServer(async (request, response) => {
    const chunks: Buffer[] = [];
    for await (const chunk of request) chunks.push(Buffer.from(chunk));
    expect(request.method).toBe("POST");
    expect(request.url).toBe("/api/allocate");
    expect(request.headers["content-type"]).toContain("application/json");
    const body: McpProductAllocationRequest = JSON.parse(Buffer.concat(chunks).toString());
    requests.push(body);
    response.setHeader("Content-Type", "application/json");
    response.end(JSON.stringify({
      isError: requests.length > 1,
      content: [{
        type: "text",
        text: requests.length > 1 ? "Ollama read timed out" : JSON.stringify({
          status: "ALLOCATION_PROCESSED",
          message: "Allocation processed with a shortage.",
          selectedProduct: { sku: "MAKI-KOMKOMMER", title: "Maki Komkommer", similarity: 1, equivalent: false },
          candidates: [],
          sources: [],
          allocationRun: {
            runId: "550e8400-e29b-41d4-a716-446655440000",
            openBackorderCount: 1,
            allocations: [{
              orderId: body.orderId, sku: "MAKI-KOMKOMMER", requestedQuantity: body.quantity,
              allocatedFromInventory: 30, allocatedFromInbound: 10, backorderedQuantity: 10,
              status: "PARTIALLY_BACKORDERED",
            }],
          },
        }),
      }],
    }));
  });
  const backendUrl = new URL(process.env.STOCKIO_API_URL ?? "");
  if (backendUrl.hostname !== "127.0.0.1" || !backendUrl.port || backendUrl.port === "8082") {
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
    expect(requests[0]).toMatchObject({
      orderId: "ORDER-UI-001", customer: "Example Customer", productQuery: "Maki Komkommer",
      quantity: 50, priority: 1, allowEquivalent: true,
    });
    expect(requests[0].requestId).toMatch(/^[0-9a-f-]{36}$/);
    expect(requests[0].placedDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);

    await page.getByRole("button", { name: "Allocate product", exact: true }).click();
    await expect(page.getByRole("alert", { name: "Request could not be completed" })).toContainText("Ollama read timed out");
    expect(requests[1].requestId).toBe(requests[0].requestId);

    await page.getByLabel("Quantity", { exact: true }).fill("51");
    await page.getByRole("button", { name: "Allocate product", exact: true }).click();
    await expect(page.getByRole("alert", { name: "Request could not be completed" })).toContainText("Ollama read timed out");
    expect(requests[2].requestId).not.toBe(requests[0].requestId);
    expect(requests[2].quantity).toBe(51);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  } finally {
    await new Promise<void>((resolve, reject) => {
      backend.close((error) => error ? reject(error) : resolve());
      backend.closeAllConnections();
    });
  }
});
