# Feature: Introduce ktlint static analysis

- **Status:** Done
- **Priority:** Not set
- **Bounded Context:** Build and static analysis
- **Implementation started:** Yes. Plugin, EditorConfig, formatting, and CI wiring are in place.
- **Specification:** [Feature specification](../../../docs/specifications.md#feature-introduce-ktlint-static-analysis)
- **Parent Specs:** [Architecture](../../../docs/architecture.md)
- **Scenario:** [Scenario 24](../../../docs/scenarios.md#scenario-24-check-kotlin-style-with-ktlint)

## Context and scope

Stockio has about 146 hand-written Kotlin files across `:stockio-service`,
`:stockio-api`, and `:stockio-test-harness`. The root build is Groovy. Kotlin
is 2.2.20 and the wrapper is Gradle 8.14.3. There is no ktlint, Spotless,
detekt, or `.editorconfig`. CI runs `:stockio-service:check` and
`:stockio-api:check`. It does not run the harness.

Generated OpenAPI interfaces are written to
`stockio-api/build/generated/openapi/` and added to the service Kotlin source
set. The Next.js UI lives under the API module and has no `.kt` files. The
harness `performance` source set is custom Kotlin.

In scope:

- `org.jlleitschuh.gradle.ktlint` 14.2.0 on every subproject, with the ktlint
  engine pinned to 1.8.0;
- a root `.editorconfig` with 2-space indentation, line length off, and
  trailing-comma rules disabled;
- formatting existing hand-written Kotlin so `ktlintCheck` passes with no
  baseline;
- CI coverage for the service, API, and harness style check;
- architecture and README entries after the tasks exist.

Out of scope:

- ktlint 2.0 alphas;
- a violation baseline;
- linting generated OpenAPI Kotlin or the Next.js tree;
- running the harness Testcontainers suite as part of this gate;
- detekt or other static analyzers;
- product behavior, APIs, or persistence changes.

## Technical analysis of the current build

1. **Plugin application matches the existing root build.** Plugins are declared
   on the root with `apply false`, then applied inside `subprojects` in
   [build.gradle](../../../build.gradle). ktlint follows that pattern.
2. **`check` already gates the service and API.** The plugin adds `ktlintCheck`
   as a dependency of `check`. The current CI Gradle job picks that up for
   `:stockio-service` and `:stockio-api`.
3. **The harness needs an explicit task.** Adding `:stockio-test-harness:check`
   would run Testcontainers. CI should run `:stockio-test-harness:ktlintCheck`
   only. That task also covers the `performance` source set.
4. **Generated sources are outside the service directory.** The service
   compiles `../stockio-api/build/generated/openapi/...`. Filters for
   `**/generated/**` and `**/build/**` keep those files out of format and check.
5. **Current indentation is 4 spaces.** The agreed EditorConfig uses 2. The
   format diff will be large and must stay limited to style: indentation,
   import order, spacing, wrapping, and removal of trailing commas.
6. **Unset `ktlint_code_style` is intentional.** ktlint 1.8 then keeps
   `ktlint_official` for rules the EditorConfig does not disable.

Agreed `.editorconfig`:

```editorconfig
root = true

[{*.kt,*.kts,*.kt.ftl}]
max_line_length = off
indent_style = space
indent_size = 2
ktlint_standard_trailing-comma-on-call-site = disabled
ktlint_standard_trailing-comma-on-declaration-site = disabled
```

## Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Gradle ktlint plugin | Apply `org.jlleitschuh.gradle.ktlint` 14.2.0 to every subproject and pin the ktlint engine to 1.8.0. |
| FR-02 | Exclude generated sources | Exclude `**/generated/**` and `**/build/**` from format and check, including OpenAPI interfaces compiled into the service. |
| FR-03 | EditorConfig | Add the agreed root `.editorconfig`: 2-space indent, `max_line_length` off, and trailing-comma rules disabled on call sites and declaration sites. |
| FR-04 | Format existing Kotlin | Format hand-written main, test, and harness performance sources so `ktlintCheck` passes without a baseline. |
| FR-05 | CI gate | Keep `ktlintCheck` on the service and API `check` tasks, and run `:stockio-test-harness:ktlintCheck` in CI without the harness test suite. |
| FR-06 | Document the tasks | After the tasks exist, name `ktlintCheck` and `ktlintFormat` in the architecture stack line and the README check table. |

## Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Style-only diff | Formatting changes indentation, imports, spacing, wrapping, and trailing commas. It does not change program behavior. |
| NFR-02 | Stable ktlint release | Use ktlint 1.8.0. Do not adopt a 2.0 alpha. |
| NFR-03 | Unset code style | Leave `ktlint_code_style` unset so ktlint 1.8 keeps `ktlint_official` for every rule that EditorConfig does not disable. |

## Implementation plan

1. Apply the plugin in `build.gradle` and exclude generated and build outputs.
2. Add the root `.editorconfig`.
3. Run `ktlintFormat` and fix violations the formatter cannot correct.
4. Add the harness `ktlintCheck` to CI and update architecture, README, and active context.
5. Run `ktlintCheck` and compile the three modules, then review the format diff.

## Affected components

- [build.gradle](../../../build.gradle)
- `.editorconfig` (new)
- [.github/workflows/ci.yml](../../../.github/workflows/ci.yml)
- [docs/architecture.md](../../../docs/architecture.md)
- [README.md](../../../README.md)
- Hand-written `*.kt` under `stockio-service`, `stockio-api`, and `stockio-test-harness`, including `performance`

## Risks and compatibility

- The 2-space reformat touches every hand-written Kotlin file and will dominate
  review. String templates and multiline strings need a pass so indentation
  inside them is not treated as a logic change.
- Some ktlint 1.8 rules are not autocorrected. Those need a manual edit before
  `ktlintCheck` can pass.
- A filter that misses `stockio-api/build/generated` would format generated
  interfaces and then lose those edits on the next OpenAPI generation.
- Plugin 14.2.0 and engine 1.8.0 are the versions checked against Gradle 8.14.3
  and Kotlin 2.2.20. Context-parameter parsing needs at least ktlint 1.7.0;
  this repository does not need a newer alpha.

## Functional scenario

### Gherkin description
**Given** hand-written Kotlin in `:stockio-service`, `:stockio-api`, and `:stockio-test-harness` \
**And** generated OpenAPI Kotlin under the API build directory \
**When** CI or a developer runs the Gradle ktlint check \
**Then** style violations in hand-written Kotlin fail the check \
**And** generated OpenAPI sources are not part of the check \
**And** `ktlintFormat` rewrites hand-written Kotlin to the repository EditorConfig

### Acceptance Criteria

- All three modules fail `ktlintCheck` when hand-written Kotlin breaks the EditorConfig or the enabled ktlint rules.
- Generated OpenAPI Kotlin is excluded from both format and check.
- Hand-written Kotlin is formatted to 2-space indentation with trailing commas removed, and no baseline file is required.
- CI runs the service and API checks, which include `ktlintCheck`, and runs `:stockio-test-harness:ktlintCheck` without the harness Testcontainers suite.
- The service, API, and harness Kotlin sources still compile after formatting.
- The architecture stack line and the README check table name `ktlintCheck` and `ktlintFormat` only after those tasks exist.

## Delivered

- `org.jlleitschuh.gradle.ktlint` 14.2.0 is applied to every subproject with the ktlint engine pinned to 1.8.0. Generated and build outputs are excluded. The service main ktlint tasks also drop `build/generated` source directories so they do not consume OpenAPI generator output.
- The root `.editorconfig` sets 2-space indentation, turns line length off, and disables both trailing-comma rules.
- Hand-written Kotlin is formatted. Two publisher files were renamed so the filename matches the single class: `AllocationRequestPublisher.kt` and `AllocationJsonSchemaSerializer.kt`. Wildcard test imports were replaced with explicit imports.
- CI runs `:stockio-service:check`, `:stockio-api:check`, and `:stockio-test-harness:ktlintCheck`.
- `./gradlew ktlintCheck` passed. Main and test Kotlin compilation passed for the service, API, and harness, including `compilePerformanceKotlin`. Multiline strings that use `trimIndent()` keep the same trimmed value.

## Implementation acceptance criteria

- `./gradlew ktlintCheck` passes.
- `./gradlew :stockio-service:compileKotlin :stockio-api:compileKotlin :stockio-test-harness:compileKotlin :stockio-test-harness:compilePerformanceKotlin` passes.
- The format diff contains style changes only.
