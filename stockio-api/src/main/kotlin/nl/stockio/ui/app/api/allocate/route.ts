import axios from "axios";
import { allocateProduct } from "../../../lib/product-allocation-client.ts";

export const runtime = "nodejs";

export async function POST(request: Request): Promise<Response> {
  const body = await request.text();
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
