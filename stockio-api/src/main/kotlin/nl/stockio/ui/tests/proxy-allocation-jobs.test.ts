import assert from "node:assert/strict";
import { createServer, type RequestListener } from "node:http";
import test, { type TestContext } from "node:test";
import axios, { AxiosError, type AxiosRequestConfig } from "axios";
import { POST } from "../app/api/allocation-jobs/route.ts";

const body = JSON.stringify({
  requestId: "550e8400-e29b-41d4-a716-446655440000",
  asOfDate: "2026-11-02",
  runType: "NEW_DEMAND",
  demandOrders: [{
    orderId: "ORDER-UI-001",
    customer: "Example Customer",
    priority: 1,
    placedDate: "2026-11-02",
    lines: [{
      orderLineId: "ORDER-UI-001-line-1",
      sku: "MAKI-KOMKOMMER",
      requestedQuantity: 50,
      requiredDeliveryDate: "2026-11-04",
    }],
  }],
  inboundShipments: [],
});
const request = (asyncMode?: "true" | "false" | "invalid") =>
  new Request(
    `http://127.0.0.1:3000/api/allocation-jobs${asyncMode ? `?async=${asyncMode}` : ""}`,
    { method: "POST", body },
  );

function configureUrl(context: TestContext, value: string | undefined) {
  const previous = process.env.STOCKIO_API_URL;
  if (value === undefined) delete process.env.STOCKIO_API_URL;
  else process.env.STOCKIO_API_URL = value;
  context.after(() => {
    if (previous === undefined) delete process.env.STOCKIO_API_URL;
    else process.env.STOCKIO_API_URL = previous;
  });
}

async function service(context: TestContext, listener: RequestListener) {
  const server = createServer(listener);
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  context.after(() => new Promise<void>((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
    server.closeAllConnections();
  }));
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  configureUrl(context, `http://127.0.0.1:${address.port}/stockio/`);
}

for (const status of [202, 400, 413, 503, 500]) {
  test(`Axios forwards unchanged JSON to the configured allocation jobs endpoint and preserves HTTP ${status}`, async (context) => {
    let calls = 0;
    const result = status === 202
      ? { jobId: "7debf989-feb2-4c13-b91c-f177b5a3d8ab", requestId: "550e8400-e29b-41d4-a716-446655440000" }
      : { error: "The service rejected this allocation request." };
    await service(context, async (incoming, outgoing) => {
      calls++;
      assert.equal(incoming.method, "POST");
      assert.equal(incoming.url, "/stockio/api/allocation/jobs");
      assert.equal(incoming.headers["content-type"], "application/json");
      assert.equal(incoming.headers["cache-control"], "no-store");
      const chunks = [];
      for await (const chunk of incoming) chunks.push(Buffer.from(chunk));
      assert.equal(Buffer.concat(chunks).toString(), body);
      outgoing.writeHead(status, { "Content-Type": "application/json", Location: "/api/allocation/jobs/7debf989-feb2-4c13-b91c-f177b5a3d8ab" });
      outgoing.end(JSON.stringify(result));
    });
    const response = await POST(request());
    assert.equal(response.status, status);
    assert.equal(response.headers.get("Cache-Control"), "no-store");
    assert.deepEqual(await response.json(), result);
    assert.equal(response.headers.get("Location"), "/api/allocation/jobs/7debf989-feb2-4c13-b91c-f177b5a3d8ab");
    assert.equal(calls, 1);
  });
}

test("opt-in byte admission accepts the exact boundary and rejects larger bodies before Axios", async (context) => {
  const enabled = process.env.ALLOCATION_ADMISSION_ENABLED;
  const previous = process.env.ALLOCATION_ADMISSION_MAX_REQUEST_BYTES;
  process.env.ALLOCATION_ADMISSION_ENABLED = "true";
  process.env.ALLOCATION_ADMISSION_MAX_REQUEST_BYTES = String(Buffer.byteLength(body));
  context.after(() => {
    if (enabled === undefined) delete process.env.ALLOCATION_ADMISSION_ENABLED;
    else process.env.ALLOCATION_ADMISSION_ENABLED = enabled;
    if (previous === undefined) delete process.env.ALLOCATION_ADMISSION_MAX_REQUEST_BYTES;
    else process.env.ALLOCATION_ADMISSION_MAX_REQUEST_BYTES = previous;
  });
  const post = context.mock.method(axios, "post", async () => ({ status: 202, headers: {}, data: "{}" }));
  assert.equal((await POST(request())).status, 202);
  const tooLarge = await POST(new Request("http://localhost/api/allocation-jobs", { method: "POST", body: body + " " }));
  assert.equal(tooLarge.status, 413);
  assert.equal((await tooLarge.json()).code, "ALLOCATION_REQUEST_TOO_LARGE");
  assert.equal(post.mock.callCount(), 1);
  process.env.ALLOCATION_ADMISSION_MAX_REQUEST_BYTES = "2";
  assert.equal((await POST(new Request("http://localhost/api/allocation-jobs", { method: "POST", body: "\u00e9" }))).status, 202);
  assert.equal((await POST(new Request("http://localhost/api/allocation-jobs", { method: "POST", body: "\u00e9\u00e9" }))).status, 413);
  assert.equal(post.mock.callCount(), 2);
  delete process.env.ALLOCATION_ADMISSION_MAX_REQUEST_BYTES;
  await assert.rejects(POST(request()), /requires a positive/);
});

test("Axios defaults to the independent service on port 9090 with a bounded timeout", async (context) => {
  configureUrl(context, undefined);
  context.mock.method(axios, "post", async (url: string, data: string, config: AxiosRequestConfig) => {
    assert.equal(url, "http://127.0.0.1:9090/api/allocation/jobs");
    assert.equal(data, body);
    assert.equal(config.timeout, 180_000);
    return { status: 202, headers: {}, data: "{}" };
  });
  assert.equal((await POST(request())).status, 202);
});

test("forwards async query parameter values to the upstream URL", async (context) => {
  configureUrl(context, undefined);
  const post = context.mock.method(axios, "post", async (url: string) => {
    assert.match(url, /\/api\/allocation\/jobs\?async=(true|false)$/);
    return { status: 202, headers: {}, data: "{}" };
  });
  assert.equal((await POST(request("true"))).status, 202);
  assert.equal((await POST(request("false"))).status, 202);
  assert.equal(post.mock.callCount(), 2);
});

test("rejects invalid async query parameter values before calling Axios", async (context) => {
  const post = context.mock.method(axios, "post", async () => ({ status: 202, headers: {}, data: "{}" }));
  const response = await POST(request("invalid"));
  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { error: "Query parameter 'async' must be either true or false." });
  assert.equal(post.mock.callCount(), 0);
});

test("upstream redirects are not followed or exposed as browser redirects", async (context) => {
  let calls = 0;
  await service(context, (_incoming, outgoing) => {
    calls++;
    outgoing.writeHead(307, { Location: "/another-allocation-endpoint" });
    outgoing.end("Redirect rejected.");
  });
  const response = await POST(request());
  assert.equal(response.status, 307);
  assert.equal(response.headers.get("Location"), "/another-allocation-endpoint");
  assert.equal(await response.text(), "Redirect rejected.");
  assert.equal(calls, 1);
});

test("non-JSON service content is preserved for explicit frontend rejection", async (context) => {
  await service(context, (_incoming, outgoing) => {
    outgoing.writeHead(202, { "Content-Type": "text/plain" });
    outgoing.end("Not JSON");
  });
  const response = await POST(request());
  assert.equal(response.headers.get("Content-Type"), "text/plain");
  await assert.rejects(response.json(), SyntaxError);
});

test("connection failures and Axios timeout codes return non-success responses without retries", async (context) => {
  context.mock.method(console, "error", () => {});
  for (const [code, status] of [
    ["ECONNREFUSED", 502], ["ECONNABORTED", 504], ["ETIMEDOUT", 504],
  ] as const) {
    const post = context.mock.method(axios, "post", async () => { throw new AxiosError("Upstream failed", code); });
    const response = await POST(request());
    assert.equal(response.status, status);
    assert.equal(response.headers.get("Cache-Control"), "no-store");
    assert.match(JSON.stringify(await response.json()), status === 504 ? /timed out/ : /Cannot reach/);
    assert.equal(post.mock.callCount(), 1);
    post.mock.restore();
  }
});

test("rejects invalid upstream URLs before making an HTTP request", async (context) => {
  const post = context.mock.method(axios, "post", async () => { throw new Error("Must not call upstream"); });
  configureUrl(context, undefined);
  for (const url of ["ftp://localhost", "******localhost", "http://localhost?query=1", "http://localhost#fragment"]) {
    process.env.STOCKIO_API_URL = url;
    await assert.rejects(POST(request()), /(STOCKIO_API_URL must be|Invalid URL)/);
  }
  assert.equal(post.mock.callCount(), 0);
});
