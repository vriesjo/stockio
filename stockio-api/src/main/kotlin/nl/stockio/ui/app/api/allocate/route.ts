export const runtime = "nodejs";

const apiUrl = new URL(process.env.STOCKIO_API_URL ?? "http://127.0.0.1:8082");
if (!["http:", "https:"].includes(apiUrl.protocol) || apiUrl.username || apiUrl.password) {
  throw new Error("STOCKIO_API_URL must be an HTTP(S) URL without embedded credentials.");
}
const allocationUrl = `${apiUrl.toString().replace(/\/$/, "")}/api/allocate`;

export async function POST(request: Request): Promise<Response> {
  const body = await request.text();
  try {
    const upstream = await fetch(allocationUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(180_000),
    });
    return new Response(await upstream.text(), {
      status: upstream.status,
      headers: {
        "Content-Type": upstream.headers.get("Content-Type") ?? "application/json",
        "Cache-Control": "no-store",
      },
    });
  } catch (error) {
    const timedOut = error instanceof DOMException && error.name === "TimeoutError";
    if (!(error instanceof TypeError) && !timedOut) throw error;
    console.error(`Allocation API request to ${allocationUrl} failed.`, error);
    return Response.json(
      {
        message: timedOut
          ? "The Stockio API timed out. It may still be processing this allocation; retry unchanged to reuse the request ID."
          : "Cannot reach the Stockio API. Check that it is running and STOCKIO_API_URL points to the correct host and port.",
      },
      { status: timedOut ? 504 : 502 },
    );
  }
}
