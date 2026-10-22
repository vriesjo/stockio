---
name: feature-specification
description: Prepare a repository feature specification by analyzing the codebase, documenting or updating the relevant scenario, linking it from the feature specifications, and creating a synchronized feature issue document. Use when asked to plan or prepare feature specifications before implementation.
---

# Feature Specification Workflow

Use this skill to prepare feature-planning documentation for Stockio. The
output is a specification and implementation handoff, not application code.

Do not modify application code, create a remote GitHub issue, or begin
implementation unless the user separately asks for implementation.

## Repository memory

Use the following files as the source of truth for feature work:

| File | Purpose |
| --- | --- |
| [`.github/memory/architecture.md`](.github/memory/architecture.md) | Current system architecture, C4 views, technologies, and design boundaries. |
| [`.github/memory/scenarios.md`](.github/memory/scenarios.md) | Supported user and operational flows in Gherkin, with technical analysis and acceptance criteria. |
| [`.github/memory/specifications.md`](.github/memory/specifications.md) | Feature specifications and links between features, scenarios, and architectural baselines. |
| [`.github/memory/active-context.md`](.github/memory/active-context.md) | Current work-in-progress and handoff context. |
| [`.github/memory/issues/`](.github/memory/issues/) | Feature implementation documents containing synchronized requirements and technical analysis. |
| [`.github/memory/tasks/lessons-learned.md`](.github/memory/tasks/lessons-learned.md) | Reusable process lessons discovered during repository work. |

The active feature-specification file is
`.github/memory/specifications.md`; use that filename consistently in new
documentation and links.

## General operating rules

1. Inspect the repository and relevant memory files before making assumptions.
2. Preserve unrelated worktree changes. Check `git status` before editing.
3. For non-trivial work, establish the affected scenarios, specification entry,
   architecture boundaries, implementation files, and validation plan before
   changing code.
4. Prefer the existing architecture, naming, ports, adapters, services, and
   test patterns. Do not introduce a new abstraction when an existing one
   already represents the responsibility.
5. Keep documentation synchronized with implemented behavior. Do not document
   unsupported endpoints, automatic processes, or guarantees that the code does
   not provide.
6. Use subagents only when separate context is genuinely useful. Give each
   delegated task a bounded objective and do not duplicate the same investigation.

## Feature and issue management lifecycle

The lifecycle below applies when the user invokes `/create issue`, or asks to
`write a feature based on the analysis`, or requests a change that requires a
durable feature specification. The feature is not implementation-ready until
its scenario, specification, and issue document are linked.

### 1. Analyze the feature request

Base the initial feature definition on:

- the user's `/create issue` command or feature request;
- the current repository code, tests, configuration, and README;
- `architecture.md`;
- `active-context.md`;
- relevant existing entries in `scenarios.md` and `specifications.md`;
- applicable lessons in `lessons-learned.md`.

Identify the problem, bounded context, current behavior, desired behavior,
affected components, risks, dependencies, and open decisions. Ask focused
questions when status, priority, scope, compatibility, or behavior is materially
ambiguous. Do not invent a priority or mark a feature as in progress during
planning unless the user has decided that.

### 2. Add or update the functional scenario

Read the scenario structure in
[`scenarios.md`](.github/memory/scenarios.md) before editing it.

Add a scenario when the feature introduces a new user or operational flow.
Update an existing scenario when the feature changes a supported flow. Preserve
the repository template:

1. Gherkin `Given` / `When` / `Then` description;
2. high-level technical analysis;
3. acceptance criteria.

The scenario must describe observable behavior and supported interfaces. Include
relevant REST endpoints, MCP tools, jobs, events, or operational triggers only
when they exist or are explicitly part of the approved feature scope. Use a
stable heading such as `Scenario N: <name>` and keep scenario numbering unique.

### 3. Add the feature specification and link the scenario

Read the established structure in
[`specifications.md`](.github/memory/specifications.md) before editing it.

Add or update a feature entry containing:

- `## Feature: <clear feature title>`;
- `Status`;
- `Priority` when known;
- `Parent Specs`, normally linking to `architecture.md` and any relevant
  specification baseline;
- `Bounded Context`;
- a concise overview;
- functional requirements with stable `FR-01`, `FR-02`, ... IDs;
- non-functional requirements, or an explicit `Not yet` when none are defined;
- a sequenced high-level implementation plan;
- a `Resources` section linking to the relevant scenario anchor and feature issue
  document when those files exist.

The scenario link is mandatory for behavior-driven features. Use a repository
relative link, for example:

```markdown
- [Scenario: Allocate structured new demand](./scenarios.md#scenario-2-allocate-structured-new-demand)
```

Keep feature terminology, FR IDs, status, priority, and plan synchronized with
the corresponding issue document.

### 4. Create the feature issue document

Create `.github/memory/issues/feature-<short-kebab-case-title>.md` using an
existing issue document as the structural reference. Include:

- feature title, status, priority, context, and bounded context;
- the same FR/NFR requirements and implementation plan as `specifications.md`;
- a link to the scenario and the scenario's Gherkin, technical analysis, and
  acceptance criteria when the feature changes that flow;
- technical analysis of current code and configuration;
- affected components, ports, adapters, persistence, integrations, and tests;
- risks, compatibility concerns, and implementation acceptance criteria.

The issue document is an implementation handoff, not a duplicate source of
truth. When the specification or scenario changes, update the linked issue
sections in the same change.

### 5. Update active context

After preparing or changing a feature specification, update
[`active-context.md`](.github/memory/active-context.md) with:

- the feature title and links to its specification, scenario, and issue;
- a brief summary of analysis or implementation progress;
- whether implementation has started;
- next possible steps or unresolved decisions.

Do not claim implementation has started when only documentation was prepared.
Do not copy stale feature history into a new feature entry.

### 6. Implement only after scope is ready

Before changing application code:

1. Re-read the linked scenario, specification, issue document, architecture,
   and active context.
2. Confirm the requested status and priority are appropriate.
3. Update the feature status to `In-progress` in `specifications.md`.
4. Update `active-context.md` to identify the implementation focus.
5. Implement the smallest complete change that satisfies the requirements.

If implementation changes the supported behavior, update the scenario,
specification, issue document, architecture, and README as applicable rather
than leaving documentation behind the code.

## Verification

Use verification appropriate to the change:

- **Documentation-only changes:** check links, headings, anchors, synchronized
  FR IDs, and `git diff --check`; application tests are not required.
- **Kotlin/service changes:** run the smallest relevant Gradle formatting,
  compilation, and test commands, then expand validation when targeted results
  require it.
- **Architecture or interface changes:** validate affected tests and review all
  callers, adapters, configuration, and documentation surfaces.
- **Database or migration changes:** validate migration startup and the relevant
  integration tests.

Before completion, review the diff for accidental scope expansion, stale
references, unsupported behavior, missing acceptance criteria, and broken
documentation links. Record a concise reusable lesson in
`lessons-learned.md` only when the work reveals a genuinely new process pitfall;
do not add entries for routine changes.

## Invocation

In GitHub Copilot CLI, invoke this skill with the feature idea, for example:

```text
/feature-specification Automate fulfillment of open backorders after inbound supply becomes available.
```

If no detailed feature idea is provided, invoke `/feature-specification` and
collect the feature idea and any required status, priority, or scope decisions.
