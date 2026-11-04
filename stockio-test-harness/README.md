# Stockio test harness

Test-only tooling is separate from the UI and service runtimes:

```text
stockio-test-harness/
├── e2e/          # Playwright configuration and browser specs
├── performance/  # Future performance tooling; guidance only for now
└── publisher/    # Standalone Kotlin/JDBC/RabbitMQ sample publisher and tests
```

The Gradle module depends only on the shared `stockio-api` DTO library, not
`stockio-service`. It does not own migrations, listener queues, or allocation
logic. The UI and service continue to run independently.

## Browser tests

Install the UI and harness dependencies once with Node 22:

```bash
cd stockio-api/src/main/kotlin/nl/stockio/ui
nvm use
npm ci
cd ../../../../../../../stockio-test-harness
nvm use
npm ci
npx playwright install chromium
npm run typecheck
npm run test:e2e
```

From the repository root, `make test-e2e` selects Node from the harness `.nvmrc`
and runs the browser tests. Playwright builds and starts the existing UI at
`http://127.0.0.1:3100` and the spec starts a mock service at
`http://127.0.0.1:18182`. No real `stockio-service`, database, RabbitMQ, Ollama,
or Tavily instance is needed. Ports 3100 and 18182 must be free.

For IntelliJ, select `e2e/playwright.config.ts` as the Playwright configuration
and use Node 22. Direct runs also build/start the UI and default
`STOCKIO_API_URL` to the mock address. Do not configure this suite with the real
service URL: the test deliberately rejects service ports 9090/8082.

UI unit tests and its own TypeScript check remain in the UI npm package.

## Synthetic allocation publisher

Use **dedicated non-production infrastructure**. These requests create real
allocation runs, holds, and backorders when consumed by the service.

First start PostgreSQL/RabbitMQ and `stockio-service` to migrate the schema,
populate `products`, and declare the request exchange/queues. Then, separately:

```bash
# Repository root: explicitly enables test traffic until stopped with Ctrl-C.
make run-publisher

# Equivalent command:
ALLOCATION_RABBITMQ_PUBLISHER_ENABLED=true \
  ./gradlew :stockio-test-harness:bootRun

# Kafka variant:
make run-publisher-kafka

# Publisher unit/JSON tests and isolated PostgreSQL catalogue test (Docker needed):
./gradlew :stockio-test-harness:test
```

The publisher has no HTTP server, embedded service, or migrations. Publishing
is disabled by default; `:stockio-test-harness:bootRun` alone produces no
traffic. Service startup no longer includes a fake publisher.

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `ALLOCATION_RABBITMQ_PUBLISHER_ENABLED` | `false` | Explicitly enable harness scheduling. `make run-publisher` sets this to `true`. |
| `ALLOCATION_RABBITMQ_PUBLISHER_INTERVAL` | `1000` | Fixed-rate interval and initial delay, in milliseconds. |
| `HARNESS_DB_URL` | `jdbc:postgresql://localhost:5437/logistics` | Harness-only catalogue JDBC connection. |
| `HARNESS_DB_USERNAME` / `HARNESS_DB_PASSWORD` | `logistics` / `logistics` | Local development defaults; prefer a SELECT-only catalogue user. |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | Broker connection. |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `stockio` / `stockio` | Local development broker credentials. |
| `RABBITMQ_VIRTUAL_HOST` | `/` | Broker virtual host, if different from the default. |
| `ALLOCATION_RABBITMQ_EXCHANGE` | `stockio.allocation.exchange` | Existing service-owned exchange. |
| `ALLOCATION_RABBITMQ_ROUTING_KEY` | `allocation.request` | Service request routing key. |
| `ALLOCATION_KAFKA_PUBLISHER_ENABLED` | `false` | Explicitly enable harness Kafka scheduling. `make run-publisher-kafka` sets this to `true`. |
| `ALLOCATION_KAFKA_PUBLISHER_INTERVAL` | `1000` | Kafka fixed-rate interval and initial delay, in milliseconds. |
| `ALLOCATION_KAFKA_TOPIC` | `stockio.allocation.requests` | Kafka topic consumed by the service listener. |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers for harness publishing. |
| `ALLOCATION_LEDGER_SYNC_ZONE` | `UTC` | Business date zone; match the service setting. |

The pool is read-only and performs only `select sku from products`, preserving
the previous all-catalogue selection behavior. Use database permissions to
enforce SELECT-only access; a read-only connection setting is not a substitute
for least-privilege credentials. Missing/empty catalogues and broker send
failures are logged by Spring's scheduler, without sending a fallback payload.

Each tick generates a fresh `NEW_DEMAND` request with five unique
order/shipment pairs. Quantities remain random from 1, 2, 3, 5, 8, 13, and 21;
each paired shipment has the same SKU/quantity and arrives within one to three
days, by the requested delivery date. The publisher preserves shared DTO type
headers and ISO JSON dates. RabbitMQ `convertAndSend` and Kafka producer sends
do not by themselves guarantee completed allocation.

## Performance tests

See [`performance/README.md`](./performance/README.md). No performance
framework, workload, or pass/fail threshold has been selected.
