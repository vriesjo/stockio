<!-- b7e1a4c2-9d31-4e70-a6b8-1f2c3d4e5a6b -->
---
todos:
  - id: "webclient-beans"
    content: "Replace the Tavily and Ollama RestClient beans with WebClient beans that keep the current base URLs and request timeout"
    status: pending
  - id: "client-mono"
    content: "Return Mono from TavilySearchClient.search and OllamaEmbeddingClient.embed, with the same request JSON, mapping, and exceptions"
    status: pending
  - id: "allocation-chain"
    content: "Chain those Monos in ProductAllocationService and leave catalog JDBC on the blocking scheduler"
    status: pending
  - id: "tests"
    content: "Rewrite the HTTP client test onto WebClient and return Mono from the service-test fakes"
    status: pending
isProject: false
---

# Tavily and Ollama WebClient

Predecessor: [WebMVC to WebFlux](WebMVC%20to%20WebFlux-e4b8c2a1.plan.md). Start this after that edge is in place, so `WebClient` is on the classpath and `ProductAllocationController` already returns `Mono`.

This is the smallest proof that an outbound call can wait without holding a thread. Tavily and Ollama stay HTTP JSON calls. There is no transaction and no schema change. Paths, status codes, and the product-allocation response body stay the same.

`ProductAllocationService` also reads the catalog and writes embeddings through JDBC. Those sections stay on the dedicated blocking scheduler from the WebFlux plan. Only the Tavily and Ollama waits become non-blocking.

## Current calls

[`ProductResolutionClients.kt`](stockio-service/src/main/kotlin/nl/stockio/product/domain/service/ProductResolutionClients.kt):

- `TavilySearchClient.search` and `OllamaEmbeddingClient.embed` return plain lists.
- `TavilyHttpSearchClient` posts `/search` on `RestClient` with `api_key`, `query`, `search_depth: basic`, `max_results`, and `include_answer: false`.
- `OllamaHttpEmbeddingClient` posts `/api/embed` with `model` and `input`.
- Timeouts come from `ProductResolutionProperties.requestTimeout` (default 15 seconds) through `SimpleClientHttpRequestFactory`.
- Transport failures become `TavilyLookupException` or `EmbeddingClientException`. An empty body or an invalid embedding vector uses the same exception types.
- [`ProductAllocationService.allocate`](stockio-service/src/main/kotlin/nl/stockio/product/domain/service/ProductAllocationService.kt) calls `embed` and `search` directly and maps those exceptions to `EMBEDDING_FAILED` and `TAVILY_LOOKUP_FAILED`.
- [`ProductResolutionClientsTest`](stockio-service/src/test/kotlin/nl/stockio/product/resolution/ProductResolutionClientsTest.kt) binds `MockRestServiceServer` to `RestClient.Builder`.

## Target shape

```kotlin
fun interface TavilySearchClient {
    fun search(query: String): Mono<List<TavilySearchResult>>
}

fun interface OllamaEmbeddingClient {
    fun embed(texts: List<String>): Mono<List<List<Double>>>
}
```

`ProductResolutionClientConfiguration` builds two `WebClient` beans from `WebClient.Builder`. Each uses the existing base URL. The Reactor Netty client uses `requestTimeout` for connect and response. The API key, model, result limits, and similarity thresholds stay on `ProductResolutionProperties`.

`ProductAllocationService.allocate` returns `Mono<ProductAllocationResponse>`. It flatMaps the client publishers. A catalog or vector JDBC call inside that chain is `Mono.fromCallable { ... }.subscribeOn(blockingHttpScheduler)`. The WebClient publishers are not moved onto that scheduler, and nothing on this path calls `Mono.block`.

## Steps

1. **Clients.** Replace `tavilyRestClient` and `ollamaRestClient` with `WebClient` beans. Keep qualifiers distinct. Delete the `RestClient` factory once nothing in this file references it.
2. **Tavily.** `POST /search` with the same JSON fields. Map `WebClientRequestException` and `WebClientResponseException` to `TavilyLookupException("Tavily product search failed.")`. A missing body still throws `TavilyLookupException("Tavily returned an empty response.")`. Drop blank URLs and blank content the same way. A missing API key still fails before the request with `TavilyLookupException("Tavily API key is not configured.")`.
3. **Ollama.** `POST /api/embed` with the same `model` and `input`. Keep the empty-list `require`. Map transport failures to `EmbeddingClientException("Ollama embedding request failed.")`. Keep the empty-response, invalid-vector, and inconsistent-dimension checks.
4. **Service chain.** Change `allocate` to `Mono`. Preserve the exact-match short circuit, local-similarity accept, Tavily fallback, evidence-similarity reject, and the two failure statuses. `refreshProductEmbeddings` and `allocateResolvedProduct` stay blocking slices on the dedicated scheduler. `ProductAllocationController` returns that `Mono` inside the existing reactive controller signature.
5. **Tests.** Replace `MockRestServiceServer` in `ProductResolutionClientsTest` with a `WebClient` `ExchangeFunction` that records the request and returns the same JSON fixtures. Assert with `StepVerifier`. Update `ProductAllocationServiceTest` fakes to `Mono.just` and `Mono.error`. Assert the same statuses and that a failed lookup does not allocate.

## Verification

- `ProductResolutionClientsTest` sees the same Tavily and Ollama JSON bodies and maps the same result objects.
- A Tavily or Ollama transport failure still becomes `TAVILY_LOOKUP_FAILED` or `EMBEDDING_FAILED` and does not allocate.
- `POST /api/products/allocate` status codes and response schema are unchanged.
- During the outbound call, the dedicated blocking scheduler has no worker parked inside `WebClient`.

## Risks

- `Mono.block` inside `allocate`, or wrapping the whole method in one `fromCallable`, holds a worker for the entire Tavily or Ollama wait and removes the proof.
- JDBC embedding refresh and allocation still hold a worker. That wait ends only in the PostgreSQL plan.
- The default Reactor Netty client needs the same 15 second bound. An unset response timeout waits indefinitely.
