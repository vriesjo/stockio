import axios from "axios";
import { allocateProduct } from "../../../lib/product-allocation-client.ts";

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
  try {
    const upstream = await allocateProduct(body);
    return new Response(upstream.data, {
      status: upstream.status,
      headers: {
        "Content-Type": String(upstream.headers["content-type"] ?? "application/json"),
        "Cache-Control": "no-store",
      },
    });
  } catch (error) {
    if (!axios.isAxiosError(error)) throw error;
    const timedOut = error.code === "ECONNABORTED" || error.code === "ETIMEDOUT";
    console.error("Stockio allocation HTTP request failed:", error.code);
    return Response.json(
      {
        message: timedOut
          ? "The Stockio API timed out. It may still be processing this allocation; retry unchanged to reuse the request ID."
          : "Cannot reach the Stockio API. It may still be processing this allocation; retry unchanged to reuse the request ID. Check that stockio-service is running and STOCKIO_API_URL points to the correct host and port.",
      },
      { status: timedOut ? 504 : 502, headers: { "Cache-Control": "no-store" } },
    );
  }
}
