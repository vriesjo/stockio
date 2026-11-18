# Security Code Review Guideline

> **Purpose:** Reviewer standard for security-sensitive changes in Stockio. Apply it on every pull request that adds or changes an entry point, a trust boundary, persistence, messaging, file handling, configuration, secrets, or a dependency. Human and AI reviewers use the checklist in [section 14](#14-pull-request-checklist).

This guideline is original. Its review areas follow the OWASP Code Review Guide: identify what an attacker can control, trace that data to a sensitive sink, and check the control that is supposed to stop the abuse. It does not replace the other review documents.

| Document | Use it for |
| --- | --- |
| [generic-code-review-checklist-and-antipatterns-guideline.md](./generic-code-review-checklist-and-antipatterns-guideline.md) | General bug, readability, performance, and test review |
| [spring-boot-code-review-guideline.md](./spring-boot-code-review-guideline.md) | Spring Boot production-readiness |
| [kotlin+eda+outbox_code_review_guidelines.md](./kotlin+eda+outbox_code_review_guidelines.md) | Outbox atomicity, Kafka delivery, schema evolution, consumer idempotency |
| This document | Security of those same changes |

Reliability rules in the EDA guideline still apply. This document adds the security questions those rules do not ask: who is allowed to send the message, what the payload is allowed to do, and what a replay or a poisoned record exposes.

## 1. Baseline the review against

Stockio is a Kotlin Spring Boot 3 WebFlux service on Netty. PostgreSQL is the system of record. The same allocation behavior is reachable from REST, MCP, Kafka, and RabbitMQ. The facts below are the security baseline in [`docs/architecture.md`](../architecture.md). A pull request that changes one of them must say so in the description and update the architecture doc in the same change.

- The HTTP server binds to `127.0.0.1:9090` unless `SERVER_ADDRESS` overrides it. Loopback binding is the only network control. There is no application-level authentication or authorization.
- MCP clients use streamable HTTP at `/mcp`. Tool methods call the same domain services as REST.
- Actuator exposes `health` and `metrics`. `management.endpoint.health.show-details` is `never`.
- JobRunr's dashboard is enabled on port `8181` with no credentials in `application.yml`.
- Handwritten admin routes under `/api/admin` load a configured CSV, write Excel reports, and flush JobRunr storage.
- `GET /api/reservations/orders/{key}/redis` returns Redis values for an allowlist of key shapes.
- Kafka allocation consumption is on by default. RabbitMQ intake and the Redis fast path are present. Debezium stock CDC is opt-in.
- Outbound calls go to PostgreSQL, Kafka, RabbitMQ, Redis, Schema Registry, Tavily, and Ollama. Base URLs and credentials come from configuration, not from the request.
- The Next.js UI in `:stockio-api` calls the service from the server side. The browser is not given a direct privileged channel in the current design.
- Local defaults in `application.yml` exist for Postgres and RabbitMQ on localhost. A third-party credential is not a local default.

Treat "it only runs on my machine" as expired the moment a change publishes the port, changes `SERVER_ADDRESS`, adds a reverse proxy, or deploys the process off loopback.

## 2. How to review

Do this before commenting on style.

1. **Name the entry points in the diff.** REST controller, OpenAPI operation, MCP `@Tool`, Kafka listener, Rabbit listener, CDC consumer, JobRunr job, CSV path, Excel writer, Actuator, dashboard, or outbound HTTP client.
2. **Mark untrusted data.** Request bodies, path variables, headers, message payloads, file contents, environment overrides, and responses from Tavily, Ollama, Schema Registry, Redis, and the database when that data later reaches a sink.
3. **Follow it to the sink.** SQL, Redis key, file path, HTTP URL, log line, metric tag, Excel cell, Kafka record, error response, or a state change such as stock, reservation, or job deletion.
4. **Name the control.** Allowlist, size limit, parameterized query, fixed config URL, server-side permission check, or network bind. A missing control is a finding. A control that lives only in the UI, a comment, or a test is a finding.
5. **Decide the severity.**
   - **Request changes** when the diff adds or widens a way to read, change, or destroy stock, reservations, jobs, files, or secrets without a control that matches the new exposure.
   - **Request changes** when secrets, stack traces, or internal paths are written to logs, responses, or the repository.
   - **Comment** when the issue is defense in depth on a path that stays loopback-only and the PR states that assumption.

Review the whole path, including generated OpenAPI interfaces and YAML defaults. A safe controller in front of an unchecked listener is an incomplete review.

## 3. Authentication and authorization

The service does not authenticate callers. Reviewers do not invent a Spring Security filter that is not in the diff, and they do not treat the Next.js app as the authorization layer.

### Required decisions

- A new REST route, MCP tool, listener, or admin action must state its callers and its exposure: loopback only, or reachable beyond the host.
- Widening exposure beyond loopback (`SERVER_ADDRESS`, published container ports, ingress, a bind change) requires a server-side authentication and authorization design in the same change. Shipping the bind change first and the auth change later is a reject.
- Privileged behavior needs an explicit gate once authentication exists: flush JobRunr jobs, read Redis values, generate reports, run allocation, mutate stock. Method security has to cover every entry that reaches the use case, including MCP and listeners, not only the controller a reviewer happened to open.
- Object identity in the URL or payload is not proof of ownership. `jobId`, `orderId`, `runId`, and Redis key segments are guessable or replayable. When the service is exposed beyond loopback, reads and writes of those objects need a server-side check that the caller may touch that object.
- Message consumers are not authenticated users. A Kafka, RabbitMQ, or CDC record must not grant a role, a tenant, or an "admin" flag taken from the payload.
- Client-supplied headers (`X-User`, `X-Role`, a bearer token the server never verifies) are not identity.

### Admin and equivalent power

These operations are destructive or data-exporting. Any change to them, or any new operation with the same power, is security-sensitive:

- `DELETE /api/admin/allocations/flush-all-jobs`
- `POST /api/admin/allocations/generate-excel` and the `generate_excel_report` MCP tool
- `GET /api/admin/allocations/generate-request-by-csv`
- `GET /api/reservations/orders/{key}/redis`
- JobRunr dashboard actions
- Allocation and stock mutation through REST, MCP, Kafka, or RabbitMQ

## 4. Session, browser, and CSRF

The API is stateless WebFlux. There is no session cookie today.

- Introducing cookies or server-side sessions requires `Secure`, `HttpOnly`, and an explicit `SameSite` choice, plus CSRF protection for cookie-authenticated browser calls.
- Bearer tokens or mTLS do not need CSRF. Cookie sessions do.
- CORS, if added, must list origins. A wildcard origin combined with credentialed requests is a reject. Reflecting the request `Origin` header is a reject.
- The UI keeps calling the service from the server side. A change that moves the Tavily key, database password, or service credentials into a `NEXT_PUBLIC_` variable or a client bundle is a reject.
- MCP is an HTTP API for a local agent. It gets the same authorization expectations as REST once the port is reachable beyond loopback.

## 5. Input validation and injection

Validate at the boundary that first accepts the data, before domain code consumes it. HTTP bean validation does not cover Kafka, RabbitMQ, or CDC. Those listeners need the same limits and the same schema checks.

### Limits

`AllocationRequestLimits` and the admission settings are the existing size controls for allocation payloads. A new intake path must enforce count and byte limits before it allocates, publishes, or persists. Fail with the existing `413` / too-large behavior on HTTP. On messaging, reject the record to the dead-letter path instead of allocating a partial request.

### SQL and queries

- JDBC, JPA, and R2DBC queries use bind parameters.
- Reject string concatenation, `String.format`, or Kotlin interpolation that puts request data into SQL, including `ORDER BY`, table names, and `IN` lists built by splicing.
- Native queries stay static. A dynamic fragment is an allowlist of fixed column names chosen in code, never the caller's string.
- Schema changes go through Flyway. `ddl-auto` stays `validate`.

### Redis

The reservation read allowlists key shapes (`stock:sku:…`, `reservation:…`, `expiry:res:…`). Keep that allowlist. Reject a route or tool that accepts a raw Redis key, a caller-supplied prefix, or a pattern (`*`, `?`) and then `GET`s or `SCAN`s it.

### HTTP and SSRF

Outbound base URLs are configuration: Tavily, Ollama, Schema Registry, database, brokers. Reject any change that lets a request, message, or CSV cell choose the host, scheme, or port of an outbound call. Redirects to private addresses and link-local metadata addresses are in scope if a client starts following redirects.

### Other injection

- No `Runtime.exec`, `ProcessBuilder`, or shell wrappers with user data. Allocation does not need a shell.
- No Java serialization (`ObjectInputStream`) of request or message bytes. JSON and Schema Registry records stay the accepted formats.
- Log lines use parameterized logging. Reject logging raw payloads that contain credentials, and reject building a log line by concatenating untrusted text into a format string.
- SpEL, Thymeleaf, or expression evaluators must not evaluate request text.
- Excel and CSV sinks are covered in the next section. A cell or a file path is an injection sink.

## 6. Files, CSV, and Excel

Report generation writes `.xlsx` files. The MCP tool and `POST /api/admin/allocations/generate-excel` both accept `ExcelReportRequest.fileName`.

- `fileName` stays a single path segment: letters, digits, `.`, `_`, `-`, and an `.xlsx` suffix. Reject `..`, separators, and absolute paths on every entry point, including new ones.
- Resolve the file under the configured report directory and verify the normalized path is still inside that directory after resolution.
- The CSV intake path comes from `stockio.allocation.csv-path`. Callers do not choose the path. A new upload endpoint is a different feature: limit size, restrict content type, store outside the web root, and do not trust the original filename.
- Write untrusted strings (SKU, order id, status, free text) as shared strings or numeric cells. A value that starts with `=`, `+`, `-`, or `@` becomes a formula when someone opens the workbook in a spreadsheet. Prefix or escape those cells.
- POI reads and writes stay on the configured file. Disable external entity and external DTD resolution on any XML parser added for xlsx, imports, or SOAP.

Returned report paths are local filesystem paths. Do not return them to a caller that is not on the same host, and do not serve the report directory over HTTP without a separate access decision.

## 7. Data protection, errors, and logging

Allocation data includes order identifiers, SKUs, quantities, warehouse codes, and product-resolution text. Treat it as business data that should not be copied into logs, metrics, or error bodies without a reason.

### Responses

- `CustomerExceptionHandler` maps known failures to a small `code` and `error` body. New handlers follow that shape.
- Exception messages returned to clients are written for callers. They do not include SQL, host names, file paths, Redis internals, schema-registry errors, or stack traces.
- Reject `@ExceptionHandler(Exception::class)` that returns `exception.message` or the exception object.
- `404` vs `400` must not confirm the existence of another caller's order or job once object-level authorization exists. Until then, unknown ids still return `404` as the API contract says.
- Actuator `show-details` stays `never` unless the PR adds authentication in front of health details.

### Logs and metrics

- Do not log API keys, passwords, connection strings, raw `Authorization` headers, or full allocation bodies.
- Metric tags use bounded values (status code, allocation run type, exception class). Reject tags built from SKU, order id, or free text.
- Security-relevant actions need a log line that can be correlated later: admin flush, failed payload-limit rejection, listener rejection to a dead-letter topic, and any future authentication failure. Include a correlation id, not the secret and not the full record.
- Dead-letter topics and JobRunr failure storage inherit the payload. A change that puts credentials or full customer documents into those records is a reject.

## 8. Cryptography and secrets

- Credentials are injected at runtime through the environment or a secret store. Review the diff for strings that look like keys, tokens, or private keys in Kotlin, YAML, tests, fixtures, docs, and snapshots.
- Local-only placeholders for localhost Postgres and RabbitMQ may stay. A live third-party key as a YAML default is a reject, including `stockio.product-resolution.tavily-api-key`.
- Do not print secrets while debugging a connection. A temporary log of a password is still a reject.
- TLS is required for Postgres, Kafka, RabbitMQ, Redis, and HTTP clients when the peer is not on loopback. Plaintext to a remote broker is a reject.
- Redis and RabbitMQ need authentication when they are not loopback.
- Use the platform libraries for hashing and TLS. Reject hand-rolled encryption, static IVs, ECB mode, and MD5 or SHA-1 used as a password hash.
- If the service ever stores a password or a token at rest, store an adaptive hash (Argon2, BCrypt, or PBKDF2) and a unique salt. Allocation does not store end-user passwords today; do not add a reversible encoding "for convenience".

## 9. Messaging and replay

Apply the EDA guideline for transactions, idempotence, and schema compatibility. Apply this section for abuse.

- Validate and bound the payload before domain code runs. A schema-valid record can still be semantically hostile (huge line counts, unexpected run type, identifiers that collide with another job).
- Idempotent consumers still authorize the action. Deduplication is not an access-control check.
- Partition keys and message keys are data. Do not use them as a capability or a shared secret.
- Retry and dead-letter paths must not turn a poison message into an infinite loop that exhausts the database, and they must not drop it silently when the failure is a security rejection. Security rejections are terminal for that record.
- CDC repair consumers trust the Debezium topic only as far as the network and ACL around that topic. Validate the row image before it updates Redis or stock.
- A new publisher that sends straight to Kafka from a request thread, skipping the transactional outbox where one is required, is both a reliability finding and a security finding: callers can observe or force partial writes.

## 10. Configuration and operational surfaces

Review `application.yml`, env overrides, and new `@ConfigurationProperties`.

| Surface | Review expectation |
| --- | --- |
| `server.address` | Default remains `127.0.0.1`. A public bind ships with authentication. |
| Actuator `exposure.include` | Stays `health,metrics`. Reject `env`, `configprops`, `heapdump`, `mappings`, `loggers`, `threaddump`, and `*` . |
| JobRunr dashboard | Treat port `8181` as an admin UI. Enabling it on a non-loopback interface requires authentication. |
| Flyway / JPA | Migrations are forward-only and reviewed. `ddl-auto` is not `update` or `create`. |
| Kafka `auto-create` | Stays off. Topic creation is operational, not a side effect of a client. |
| Redis | Stays opt-in. A remote host requires a password and a restricted network. |
| Pool and timeout settings | Timeouts stay finite on JDBC, R2DBC, and outbound HTTP. Missing timeouts are a reliability and a denial-of-service finding. |
| Debug flags | Reject committed `debug: true`, SQL logging of bind parameters, or Actuator exposure that prints the environment. |

Admission control and request limits at `0` mean "no cap" in the current configuration. A production-oriented change that leaves byte and count limits unbounded needs an explicit comment in the PR. Otherwise require non-zero caps for any path that can be reached beyond loopback.

## 11. Dependencies and supply chain

- A Gradle version bump, a new plugin, or a new library is a security review, even when the diff is only a version line.
- Confirm the coordinates. A name that is one character off a known artifact is a reject until someone verifies the source.
- Prefer dependencies already on the Spring Boot BOM. A direct pin needs a reason.
- Reject libraries whose purpose is to disable TLS verification, parse XML insecurely by default, or shell out.
- Test fixtures and Testcontainers images are in scope. A test that bakes a real credential into the image or the repo is a reject.
- There is no documented OWASP Dependency-Check gate in this repository. Until one exists, reviewers read the dependency diff themselves and ask for an upgrade when the PR adds a component with a known critical issue.

## 12. Tests that must accompany a security change

A security fix without a failing-then-passing test is incomplete, except for a pure configuration tightening that a test cannot see.

- Limits: oversized body, too many lines, and the matching HTTP status or dead-letter outcome.
- Paths: `../`, absolute paths, and odd suffixes on `fileName`, asserted rejected.
- Queries: a regression test that the repository method binds parameters when the change touched SQL construction.
- Redis: a key outside the allowlist returns `400` and does not call the server with that key.
- Errors: a handler test that the response body has no stack trace and no internal path.
- Authorization, once it exists: an authenticated caller who does not own the `orderId` or `jobId` is denied, on REST and on any other entry that reaches the same use case.
- Secrets: tests use obvious fake values. They do not embed live keys.

## 13. What this guideline does not ask reviewers to do

- Do not demand Spring Security annotations on code that the project has not adopted.
- Do not block a loopback-only refactor for lacking an identity provider. Do block a change that removes the loopback assumption without replacing it.
- Do not duplicate EDA delivery comments here. Link the finding to the EDA guideline and add only the security impact.
- Do not treat a green test suite as evidence that authorization exists.

## 14. Pull request checklist

Copy the items that apply. Leave a short note on any item you could not verify.

### Exposure

- [ ] Entry points touched by the diff are listed (REST, OpenAPI, MCP, Kafka, RabbitMQ, CDC, job, file, Actuator, dashboard).
- [ ] The PR states whether the service remains bound to loopback.
- [ ] A non-loopback bind, published port, or ingress change includes server-side authentication and authorization.
- [ ] New admin or data-export behavior has a named gate, or an explicit loopback-only limitation written into `docs/architecture.md`.

### Untrusted data

- [ ] HTTP, message, file, and outbound-response inputs are validated before use.
- [ ] Count and byte limits apply on every new allocation intake path.
- [ ] SQL, R2DBC, and JDBC use bind parameters.
- [ ] Redis keys stay on the existing allowlist.
- [ ] Outbound URLs are not caller-controlled.
- [ ] Excel `fileName` cannot escape the report directory, and formula-looking cell values are neutralized.
- [ ] No command execution and no Java deserialization of external bytes.

### Secrets and observability

- [ ] The diff contains no live credentials, tokens, or private keys.
- [ ] Logs, metrics, dead-letter records, and error bodies exclude secrets, stack traces, and internal paths.
- [ ] Actuator exposure and JobRunr dashboard exposure are unchanged, or tightened, or authenticated.
- [ ] Remote Postgres, Kafka, RabbitMQ, and Redis use TLS and authentication.

### Messaging

- [ ] Consumers reject hostile payloads terminally and still enforce the EDA idempotency rules.
- [ ] Payload fields are not used as roles or capabilities.

### Tests

- [ ] Negative tests cover the new boundary (limits, path, key allowlist, error body, or authorization).

## References

- OWASP Code Review Guide project: https://owasp.org/www-project-code-review-guide/
- OWASP Application Security Verification Standard (control categories for authn, authz, validation, and cryptography): https://owasp.org/www-project-application-security-verification-standard/
- Stockio architecture baseline: [`docs/architecture.md`](../architecture.md)
- Sibling review guidelines in this directory
