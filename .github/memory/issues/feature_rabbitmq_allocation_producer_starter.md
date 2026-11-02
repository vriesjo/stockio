# Feature: Add a reusable RabbitMQ allocation producer starter

## Status and scope

- **Status:** Draft
- **Priority:** Not set
- **Bounded Context:** Messaging Integration & Allocation Contracts
- **Specification:** [`../specifications.md`](../specifications.md#feature-add-a-reusable-rabbitmq-allocation-producer-starter)
- **Scenario:** [`Scenario 11: Publish an Allocation Request from Another Spring Boot Application`](../scenarios.md#scenario-11-publish-an-allocation-request-from-another-spring-boot-application)

Provide a reusable Spring Boot producer starter that lets another application
publish allocation requests compatible with Stockio's existing RabbitMQ
listener. The host application supplies its Rabbit connection configuration and
the allocation exchange/routing key, then uses a typed publisher from the
starter.

The proposed deliverables are:

- `stockio-allocation-contracts`: versioned wire DTOs for allocation requests.
- `stockio-allocation-rabbitmq-producer-spring-boot-starter`: the typed publisher,
  configuration properties, and Spring Boot auto-configuration.

This is producer-only. Keep sample-data creation and scheduling in
`stockio-test-harness/publisher`, and keep the listener, queue/dead-letter topology, and
JobRunr fallback in the consumer application.

## Scenario

### Gherkin description

**Given** another Spring Boot application includes the Stockio allocation
RabbitMQ producer starter and has configured its broker connection, exchange,
and routing key \
**When** that application publishes an `AllocationRequest` through the starter's
publisher \
**Then** the starter sends a JSON message compatible with Stockio's allocation
request contract to the configured exchange and routing key \
**And** the existing Stockio RabbitMQ listener can consume and process the
message

### High-level technical analysis

- The existing `AllocationRequestPublisher` is not a reusable sender as-is: it
  also generates sample data using a harness-only JDBC catalogue connection,
  `Clock`, and an opt-in scheduled method.
- `AllocationRequest` and its nested request models currently live in the
  shared `stockio-api` DTO library under `nl.stockio.api.dto`, not the executable
  service module.
- A contracts artifact must provide the wire types without requiring consumers
  to depend on the service's persistence, web, JobRunr, and application code.
- The starter should provide a publisher backed by the host application's
  Spring AMQP `RabbitTemplate`, with exchange and routing key bound from
  configuration.
- Auto-configuration should use Boot's `@AutoConfiguration` and
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
  It should be conditional on the producer being enabled and should back off
  when the host supplies a publisher or message converter.
- The producer starter does not need `@EnableRabbit`, `@EnableScheduling`,
  listener declarations, queues, or dead-letter bindings. The consumer owns
  topology and handling.

### Acceptance Criteria

- An independent Spring Boot application can import the versioned contracts
  and producer starter without depending on `stockio-service`.
- With producer configuration enabled, Boot discovers the auto-configuration
  using `AutoConfiguration.imports` and exposes a typed allocation-request
  publisher.
- Publishing sends JSON through the configured exchange and routing key.
- Emitted JSON preserves field names, enum values, and date encoding accepted
  by the existing `AllocationRabbitListener`.
- Producer beans are not activated unless enabled, and custom host publisher
  and message-converter beans take precedence.
- Conversion and send exceptions are propagated; the starter does not return
  success after a failed send.
- The producer starter does not create consumer queues or dead-letter topology,
  enable scheduling, or generate sample allocation data.
- The existing scheduled sample publisher continues to produce messages
  consumable by the Stockio listener after being adapted to the starter.

## Functional Requirements

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Reusable allocation contract | Extract the allocation-request wire model into a versioned contracts module and preserve the JSON shape accepted by the existing Stockio listener. |
| FR-02 | Typed RabbitMQ publisher | Provide a publisher API that sends an allocation request through the host application's `RabbitTemplate` using configured exchange and routing-key values. |
| FR-03 | Spring Boot auto-configuration | Register producer auto-configuration through `AutoConfiguration.imports`; bind configuration properties, require explicit enablement, and back off when the host provides equivalent beans. |
| FR-04 | Reuse from Stockio sample publisher | Adapt the existing scheduled sample publisher to use the reusable publisher while keeping JDBC catalogue lookup, sample generation, and opt-in scheduling in `stockio-test-harness/publisher`. |
| FR-05 | External artifact consumption | Package the contract and producer starter as versioned, independently consumable artifacts without depending on the executable service module. |
| FR-06 | Producer-only boundary | Keep the allocation listener, queue and dead-letter topology, JobRunr fallback, and consumer retry behavior in `stockio-service`. |

## Non-Functional Requirements

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Wire compatibility | Preserve the allocation message field names, enum values, and date serialization expected by the existing listener. |
| NFR-02 | Safe opt-in and customization | Do not activate producer beans unless enabled, and allow host applications to override the publisher or message converter. |
| NFR-03 | Explicit failure behavior | Surface conversion and send exceptions; do not claim broker delivery guarantees beyond the configured `RabbitTemplate` and broker behavior. |
| NFR-04 | Library build isolation | Keep reusable modules as libraries rather than executable Spring Boot applications, with dependencies scoped to producer and contract responsibilities. |

## Technical analysis

### Current implementation

- The root `settings.gradle` includes `stockio-service`, `stockio-api`, and
  `stockio-test-harness`.
- The root `build.gradle` applies common Kotlin plugins across subprojects and
  the Spring Boot application plugin only to the service and harness
  publisher. Preserve that boundary for future reusable libraries.
- `AllocationRequestPublisher` in
  `stockio-test-harness/publisher/src/main/kotlin/nl/stockio/harness/publisher/AllocationRequestPublisher.kt`
  depends on `RabbitTemplate`, `JdbcTemplate`, `Clock`, exchange/routing-key
  values, and `@Scheduled`; it creates five random sample orders and shipments
  before calling `convertAndSend`.
- `AllocationRabbitConfiguration` and `AllocationRabbitListener` share a source
  file under
  `stockio-service/src/main/kotlin/nl/stockio/allocation/domain/job/listener/AllocationRabbitListener.kt`.
  They declare the consumer exchange, request queue, dead-letter exchange and
  queue, bindings, message converter, and listener retry/fallback behavior.
- Consumer Rabbit connection/exchange/routing-key/queue settings remain in
  `stockio-service/src/main/resources/application.yml`; the harness has its own
  publisher connection/exchange/routing-key/enablement settings.
- The message wire types already live in the `stockio-api` library under
  `nl.stockio.api.dto`. A further contracts extraction must preserve JSON,
  type-header compatibility, and both service and harness imports.

### Proposed implementation

1. Create a contracts module containing only the allocation request message
   types needed by producers and consumers: `AllocationRequest`,
   `AllocationRunType`, `DemandOrder`, `DemandOrderLine`, `InboundShipment`, and
   `InboundShipmentLine`. Keep unrelated request DTOs in the existing API library.
2. Add a producer starter module with a public publisher API such as
   `AllocationRequestPublisher.publish(request)`, an immutable
   `@ConfigurationProperties` model, and a conditional auto-configuration.
3. Use a documented property namespace such as
   `stockio.messaging.rabbitmq.allocation.producer`; include explicit
   enablement plus exchange and routing-key values. Use the existing
   Spring Boot-managed `RabbitTemplate` and Jackson configuration where
   available; supply a default message converter only when no host converter
   exists.
4. Register the auto-configuration class in
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
   Guard it on Spring AMQP availability and explicit enablement, and use
   `@ConditionalOnMissingBean` for replaceable publisher/converter beans.
5. Refactor Gradle plugin application so the service remains executable while
   contracts and starter are libraries. Add the modules to `settings.gradle`,
   give them independent artifact coordinates/versions, and configure
   publication for external consumption.
6. Adapt the Stockio scheduled sample publisher to construct the same requests
   and delegate sending to the starter. Keep its product lookup, sample
   generation, scheduling, and explicit operational enablement in
   `stockio-test-harness/publisher`.
7. Leave `AllocationRabbitListener`, consumer queue/DLX declarations, and
   JobRunr fallback in `stockio-service`.

### Configuration example

The consuming application should provide Rabbit connection details using
Spring Boot's normal `spring.rabbitmq.*` properties and opt into the allocation
producer, for example:

```yaml
stockio:
  messaging:
    rabbitmq:
      allocation:
        producer:
          enabled: true
          exchange: stockio.allocation.exchange
          routing-key: allocation.request
```

Final property names should be implemented through the starter's
`@ConfigurationProperties` type and documented with the published artifact.

### Tests and verification

- Auto-configuration tests verify enabled/disabled behavior, conditional class
  presence, and host-bean backoff.
- Publisher tests verify the configured exchange and routing key, typed payload
  conversion, and propagation of conversion/send errors.
- Contract serialization tests verify JSON property names, enum values, and
  dates against the existing listener's accepted payload.
- A harness test verifies the scheduled sample publisher delegates to
  the reusable publisher and still constructs valid messages.
- Build checks verify the new modules are libraries, artifacts can be
  published/consumed in a local test setup, and the service consumer compiles
  against the extracted contract.

### Risks, compatibility, and open decisions

- **Artifact repository:** No publication repository is configured in the
  current build. Choose the Maven repository, credentials source, and release
  versioning policy before external release; never commit repository secrets.
- **Wire compatibility:** Changing Kotlin package names during DTO extraction
  must not change serialized property names, enum strings, or date format.
- **Topology ownership:** The producer starter should publish to an existing
  exchange and should not create service-owned consumer queues or dead-letter
  bindings.
- **Delivery semantics:** `RabbitTemplate.convertAndSend` does not by itself
  establish a publisher-confirm guarantee. This feature preserves explicit
  exception behavior and does not promise broker confirmation unless separately
  configured.
- **Gradle plugin scope:** Refactoring root plugin application must retain
  current `stockio-service` and `stockio-api` build/run behavior.
- **Operational compatibility:** Preserve the existing sample publisher's
  enablement/interval behavior and the consumer's routing and dead-letter
  behavior.

## Implementation plan

1. Extract the allocation request wire types into a versioned
   `stockio-allocation-contracts` module and update service imports without
   changing the existing JSON contract.
2. Add a reusable RabbitMQ producer starter with configuration properties, a
   typed publisher, conditional auto-configuration, and the
   `AutoConfiguration.imports` registration.
3. Configure the starter as a library module, add artifact
   publication/versioning support, and adjust root Gradle plugin application
   so application plugins are not forced onto library modules.
4. Adapt the scheduled sample publisher to call the reusable publisher; retain
   JDBC catalogue lookup and opt-in scheduling in
   `stockio-test-harness/publisher`, and retain listener topology and consumer
   behavior in the service.
5. Add focused auto-configuration, serialization, send-routing, override,
   failure, and compatibility tests, plus a consumer-compatible message test.

## Acceptance criteria for implementation

- A separate Spring Boot project can import the published artifacts, configure
  a broker, exchange, and routing key, and publish an allocation request.
- The stockio service consumes the emitted JSON through its existing listener
  without contract changes.
- Producer auto-configuration is discovered using
  `AutoConfiguration.imports`, is opt-in, and supports host bean overrides.
- Sample generation and consumer topology remain outside the reusable
  producer starter.
- The producer and contract artifacts build and publish independently from the
  executable service module.
