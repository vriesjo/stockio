import { defineConfig } from "@playwright/test";
import { fileURLToPath } from "node:url";

process.env.STOCKIO_API_URL ??= "http://127.0.0.1:18182";

export default defineConfig({
  testDir: ".",
  workers: 1,
  use: {
    baseURL: "http://127.0.0.1:3100",
  },
  webServer: {
    command: "npm run build && npm run start -- --port 3100",
    cwd: fileURLToPath(new URL("../../stockio-api/src/main/kotlin/nl/stockio/ui/", import.meta.url)),
    url: "http://127.0.0.1:3100",
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
