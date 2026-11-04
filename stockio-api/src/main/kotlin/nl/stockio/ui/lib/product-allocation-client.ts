import axios from "axios";

export async function allocateProduct(body: string) {
  const apiUrl = new URL(process.env.STOCKIO_API_URL ?? "http://127.0.0.1:9090");
  if (!["http:", "https:"].includes(apiUrl.protocol) || apiUrl.username || apiUrl.password ||
      apiUrl.search || apiUrl.hash) {
    throw new Error("STOCKIO_API_URL must be an HTTP(S) base URL without credentials, query, or fragment.");
  }
  return axios.post<string>(
    `${apiUrl.toString().replace(/\/$/, "")}/api/products/allocate`,
    body,
    {
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
      timeout: 180_000,
      maxRedirects: 0,
      responseType: "text",
      validateStatus: () => true,
    },
  );
}
