import axios from "axios";
import { submitAllocationJob } from "../../../lib/allocation-job-client.ts";

export const runtime = "nodejs";

async function allocationBody(request: Request): Promise<string | Response> {
  if (process.env.ALLOCATION_ADMISSION_ENABLED !== "true") return request.text();
  const configured = process.env.ALLOCATION_ADMISSION_MAX_REQUEST_BYTES ?? "";
  const limit = Number(configured);
  if (!/^[0-9]+$/.test(configured) || !Number.isSafeInteger(limit) || limit <= 0 || limit >= 2_147_483_647) {
    throw new Error("Enabled admission requires a positive ALLOCATION_ADMISSION_MAX_REQUEST_BYTES below Int.MAX_VALUE.");
  }
  const tooLarge = () => Response.json(
    { code: "ALLOCATION_REQUEST_TOO_LARGE", error: "Allocation request exceeds the configured byte limit." },
    { status: 413, headers: { "Cache-Control": "no-store" } },
  );
  if (Number(request.headers.get("content-length")) > limit) return tooLarge();
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let bytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      if (bytes > limit) {
        await reader.cancel().catch(() => {});
        return tooLarge();
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  return new TextDecoder().decode(Buffer.concat(chunks));
}

export async function POST(request: Request): Promise<Response> {
  const body = await allocationBody(request);
  if (body instanceof Response) return body;
  const asyncQuery = new URL(request.url).searchParams.get("async");
  if (asyncQuery !== null && asyncQuery !== "true" && asyncQuery !== "false") {
    return Response.json(
      { error: "Query parameter 'async' must be either true or false." },
      { status: 400, headers: { "Cache-Control": "no-store" } },
    );
  }
  try {
    const upstream = await submitAllocationJob(
      body,
      asyncQuery === null ? undefined : asyncQuery === "true",
    );
    const location = upstream.headers.location ?? upstream.headers.Location;
    const headers: Record<string, string> = {
      "Content-Type": String(upstream.headers["content-type"] ?? "application/json"),
      "Cache-Control": "no-store",
    };
    if (typeof location === "string" && location.trim()) headers.Location = location;
    return new Response(upstream.data, {
      status: upstream.status,
      headers,
    });
  } catch (error) {
    if (!axios.isAxiosError(error)) throw error;
    const timedOut = error.code === "ECONNABORTED" || error.code === "ETIMEDOUT";
    console.error("Stockio allocation jobs HTTP request failed:", error.code);
    return Response.json(
      {
        message: timedOut
          ? "The Stockio allocation jobs API timed out. It may still be processing this request; retry unchanged to reuse the request ID."
          : "Cannot reach the Stockio allocation jobs API. It may still be processing this request; retry unchanged to reuse the request ID. Check that stockio-service is running and STOCKIO_API_URL points to the correct host and port.",
      },
      { status: timedOut ? 504 : 502, headers: { "Cache-Control": "no-store" } },
    );
  }
}
