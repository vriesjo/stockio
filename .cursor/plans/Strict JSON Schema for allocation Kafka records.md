# Plan

## Strict JSON Schema for allocation Kafka records

## Choice

Use Confluent JSON Schema, not Avro or Protobuf.
`infra/docker/docker-compose.yml` already runs Schema Registry 7.4 on port 8081,
and `AllocationRequest`
(`stockio-api/src/main/kotlin/nl/stockio/api/dto/AllocationRequest.kt`) is
already the Jackson model shared by REST, RabbitMQ, and Kafka. The registry
adds a schema id and validation without a second generated model.

The record value will no longer be raw JSON. Confluent framing is a magic byte,
a schema id, then the JSON document. A plain JSON payload fails deserialization
and is not retried; the existing dead-letter path publishes it. The key stays a
UUID string.

CDC on `stockio.cdc.public.stock` stays plain JSON. RabbitMQ and the REST body
stay Jackson JSON.

```text
┌─────────────────────────────────────────┐   ┌────────────────────────────────────────┐   ┌─────────────────────────┐                         ┌──────────────┐
│                                         │   │                                        │   │                         │                         │              │
│ REST async publish or harness publisher ├──►│ Schema Registry subject requests-value │◄──┤ AllocationKafkaListener ├─schema-or-shape-failure►│ requests.DLT │
│                                         │   │                                        │   │                         │                         │              │
└────────────────────┬────────────────────┘   └────────────────────────────────────────┘   └─────────────────────────┘                         └──────────────┘
                     │                                                                                  ▲
                     │                        ┌────────────────────────────────────────┐                │
                     │                        │                                        │                │
                     └───────────────────────►│      stockio.allocation.requests       ├────────────────┘
                                              │                                        │
                                              └────────────────────────────────────────┘
```

## Schema

Add a Draft-07 JSON Schema next to the DTO, for example
`stockio-api/src/main/resources/schema/allocation-request.schema.json`. It
mirrors the OpenAPI `AllocationRequest` tree: required `requestId`, `asOfDate`,
and `runType`; UUID and date formats; the `NEW_DEMAND` /
`BACKORDER_FULFILLMENT` enum; nested orders and shipments with the current
required fields and limits.

Every object sets `additionalProperties` to `false`. `demandOrders` and
`inboundShipments` stay optional and default to empty arrays, matching the
Kotlin defaults.

Subject names, using `TopicNameStrategy`:

- `stockio.allocation.requests-value`
- `stockio.allocation.requests.DLT-value` so a valid request can be republished
  to the dead-letter topic

On startup, when Kafka allocation is enabled, register that schema and set the
subject compatibility to `FULL`. Registration uses the Schema Registry HTTP API.
If the schema is already registered unchanged, startup continues. If the
registry rejects it as incompatible, startup fails.
`auto.register.schemas` stays false, so a running producer cannot publish a new
schema version by itself.

## Serialization

Add `io.confluent:kafka-json-schema-serializer:7.4.0` to `:stockio-service` and
`:stockio-test-harness:publisher`, with the Confluent Maven repository. Align
the version with the Compose image.

In `AllocationKafkaConfiguration.kt`
(`stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/listener/AllocationKafkaConfiguration.kt`):

- Change `KafkaTemplate` from `KafkaTemplate<String, String>` to
  `KafkaTemplate<String, AllocationRequest>`.
- Build `KafkaJsonSchemaSerializer` and `KafkaJsonSchemaDeserializer` with the
  application `ObjectMapper` so `LocalDate`, `UUID`, and Kotlin data classes
  match the schema. The Confluent default mapper does not.
- Producer and consumer: `schema.registry.url` from `SCHEMA_REGISTRY_URL`
  (default `http://localhost:8081`), `auto.register.schemas=false`,
  `use.latest.version=true`, `json.fail.invalid.schema=true`.
- Deserializer type is `AllocationRequest`. Wrap it in Spring's
  `ErrorHandlingDeserializer` so a bad frame reaches the error handler instead
  of killing the consumer.
- Add schema and serialization failures to the non-retryable exceptions beside
  `IllegalArgumentException` and `AllocationRequestTooLargeException`.
- Register the same schema on the dead-letter subject. Deserialization failures
  are forwarded as the original bytes from the deserializer exception header, so
  an invalid payload is not forced through the schema again.

`AllocationKafkaListener.kt`
(`stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/listener/AllocationKafkaListener.kt`)
receives `ConsumerRecord<String, AllocationRequest>`. It no longer parses JSON.
The byte limit uses `serializedValueSize()`, which includes the 5-byte schema
header, so the limit applies to the framed record. A null value or a key that
is not a UUID is still an `IllegalArgumentException`.

`AllocationJobExecutor.kt`
(`stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/AllocationJobExecutor.kt`)
sends the `AllocationRequest` object. The key remains the new job id.

`AllocationKafkaRequestPublisher.kt`
(`stockio-test-harness/publisher/src/main/kotlin/nl/stockio/harness/publisher/AllocationKafkaRequestPublisher.kt`)
sends the object with the same serializer settings.
`AllocationSampleRequestFactory.kt`
(`stockio-test-harness/publisher/src/main/kotlin/nl/stockio/harness/publisher/AllocationSampleRequestFactory.kt`)
still builds the Kotlin request; it does not choose the wire format.

Point Kafka UI at `http://schema-registry:8081` so the registered subject is
visible.

## Tests and docs

- Unit tests that mock `KafkaTemplate<String, String>` or pass a JSON string
  into the listener expect `AllocationRequest` instead. That includes
  `AllocationJobExecutorTest.kt`
  (`stockio-service/src/test/kotlin/nl/stockio/allocation/domain/job/AllocationJobExecutorTest.kt`),
  `AllocationKafkaListenerTest.kt`
  (`stockio-service/src/test/kotlin/nl/stockio/allocation/domain/job/listener/AllocationKafkaListenerTest.kt`),
  and `AllocationKafkaRequestPublisherTest.kt`
  (`stockio-test-harness/publisher/src/test/kotlin/nl/stockio/harness/publisher/AllocationKafkaRequestPublisherTest.kt`).
- `AllocationKafkaOffsetIntegrationTest.kt`
  (`stockio-service/src/test/kotlin/nl/stockio/allocation/domain/job/listener/AllocationKafkaOffsetIntegrationTest.kt`)
  keeps Embedded Kafka and uses Confluent's in-process `mock://` registry. A
  test registers the schema, publishes a valid record, and asserts a raw JSON
  record is dead-lettered without a retry.
- Update Scenario 18, the architecture Kafka sentence, and active context: the
  async publisher no longer writes raw JSON; only a registry-framed
  `AllocationRequest` is valid.

## Validation

Run the executor, listener, offset, and harness publisher tests. Confirm a valid
publish stores a schema id and an extra unknown field is rejected before it
reaches allocation.
