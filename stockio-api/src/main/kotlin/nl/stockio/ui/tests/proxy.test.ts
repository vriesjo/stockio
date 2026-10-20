import assert from "node:assert/strict";
import test from "node:test";
import { POST } from "../app/api/allocate/route.ts";

test("forwards JSON to the configured Spring API and preserves its status and body", async (context) => {
  const body = '{"requestId":"550e8400-e29b-41d4-a716-446655440000","quantity":50}';
  context.mock.method(globalThis, "fetch", async (...[input, options]: Parameters<typeof fetch>) => {
    const base = process.env.STOCKIO_API_URL ?? "http://127.0.0.1:8082";
    assert.equal(input, `${new URL(base).toString().replace(/\/$/, "")}/api/allocate`);
    assert.equal(options?.method, "POST");
    assert.equal(options?.body, body);
    assert.deepEqual(options?.headers, { "Content-Type": "application/json" });
    return Response.json({ message: "Invalid request" }, { status: 400 });
  });

  const response = await POST(new Request("http://127.0.0.1:3000/api/allocate", {
    method: "POST", body,
  }));

  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { message: "Invalid request" });
  assert.equal(response.headers.get("Cache-Control"), "no-store");
});

test("connection failures and timeouts return explicit non-success responses", async (context) => {
  context.mock.method(console, "error", () => {});
  for (const [error, status] of [
    [new TypeError("Connection refused"), 502],
    [new DOMException("Request timed out", "TimeoutError"), 504],
  ] as const) {
    const fetchMock = context.mock.method(globalThis, "fetch", async () => { throw error; });
    const response = await POST(new Request("http://127.0.0.1:3000/api/allocate", {
      method: "POST", body: "{}",
    }));
    assert.equal(response.status, status);
    const body: unknown = await response.json();
    assert.equal(typeof body, "object");
    assert.match(JSON.stringify(body), status === 504 ? /timed out/ : /Cannot reach/);
    fetchMock.mock.restore();
  }
});
