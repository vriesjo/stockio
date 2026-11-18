<!-- 47d28501-6d71-4370-a49f-44239543cbd4 -->
---
todos:
  - id: "wire-plugin"
    content: "Apply ktlint 14.2.0 / engine 1.8.0 in build.gradle and exclude generated and build sources"
    status: pending
  - id: "editorconfig"
    content: "Add the agreed root .editorconfig (2-space indent, line length off, trailing commas disabled)"
    status: pending
  - id: "format"
    content: "Run ktlintFormat and fix remaining violations in hand-written Kotlin"
    status: pending
  - id: "ci-docs"
    content: "Add harness ktlintCheck to CI and update architecture, README, and active context"
    status: pending
  - id: "verify"
    content: "Run ktlintCheck and compile the three modules; review the format diff"
    status: pending
isProject: false
---
# Introduce ktlint

Add ktlint as a Gradle check on the existing Groovy build. Hand-written Kotlin is reformatted in this change so `ktlintCheck` passes with no baseline file. This is a build quality gate, so it updates the architecture stack line and the README check table rather than adding a product scenario.

## Build

In [build.gradle](build.gradle), declare `org.jlleitschuh.gradle.ktlint` `14.2.0` with `apply false`, then apply it inside the existing `subprojects` block next to the Kotlin plugin. Pin the ktlint engine to `1.8.0` (latest stable; parses Kotlin 2.2). Leave ktlint 2.0 alphas out.

```gradle
plugins {
    id 'org.jlleitschuh.gradle.ktlint' version '14.2.0' apply false
}

subprojects {
    apply plugin: 'org.jlleitschuh.gradle.ktlint'
    ktlint {
        version.set('1.8.0')
        filter {
            exclude('**/generated/**')
            exclude('**/build/**')
        }
    }
}
```

The plugin adds `ktlintCheck` as a dependency of `check`. That covers `:stockio-service` and `:stockio-api` in the current CI Gradle job. `:stockio-test-harness` is not part of that job, including its `performance` source set, so [`.github/workflows/ci.yml`](.github/workflows/ci.yml) gets an extra `:stockio-test-harness:ktlintCheck`. Do not add the harness `check` task; that would run Testcontainers in CI.

Generated controller interfaces live under `stockio-api/build/generated/openapi/` and are compiled into `:stockio-service`. The `generated` and `build` excludes keep them out of both format and check. The Next.js tree has no `.kt` files, so it is untouched.

## Style

Add a root [`.editorconfig`](.editorconfig) exactly as:

```editorconfig
root = true

[{*.kt,*.kts,*.kt.ftl}]
max_line_length = off
indent_style = space
indent_size = 2
ktlint_standard_trailing-comma-on-call-site = disabled
ktlint_standard_trailing-comma-on-declaration-site = disabled
```

`ktlint_code_style` stays unset, so ktlint 1.8 keeps its default `ktlint_official` for every other rule. Line length is explicitly off. Trailing commas are not required and the formatter removes them.

Current Kotlin is indented with 4 spaces. `ktlintFormat` will rewrite hand-written sources to 2 spaces, plus import order, spacing, and wrapping. That is the bulk of the diff. Run it across `:stockio-service`, `:stockio-api`, and `:stockio-test-harness` (main, test, and the harness `performance` source set). Fix any violations the formatter cannot autocorrect. About 146 hand-written `.kt` files will change; the diff should stay style only.

## Docs

- [docs/architecture.md](docs/architecture.md): add ktlint to the Build stack line.
- [README.md](README.md): add `./gradlew ktlintCheck` and `./gradlew ktlintFormat` to the testing table.
- [`.github/memory/active-context.md`](.github/memory/active-context.md): short note that the ktlint gate is in progress, then done after verification.

## Verification

- `./gradlew ktlintCheck`
- `./gradlew :stockio-service:compileKotlin :stockio-api:compileKotlin :stockio-test-harness:compileKotlin :stockio-test-harness:compilePerformanceKotlin`
- Scan the format diff for anything other than style before finishing.
