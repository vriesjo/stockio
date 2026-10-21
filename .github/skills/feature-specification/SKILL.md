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

Use these files as the feature-planning sources of truth:

- `.github/memory/architecture.md` — architecture, C4 views, technologies, and
  design boundaries.
- `.github/memory/scenarios.md` — supported flows using Gherkin, high-level
  technical analysis, and acceptance criteria.
- `.github/memory/specifications.md` — feature specifications and links between
  features, scenarios, and architectural baselines.
- `.github/memory/active-context.md` — current work-in-progress and handoff
  context.
- `.github/memory/issues/` — feature implementation documents.
- `.github/memory/tasks/lessons-learned.md` — reusable process lessons.

The active specification filename is `specifications.md`. Do not create new
references to `requirements-specification.md`.

## Workflow

### 1. Analyze the feature request

Read `AGENTS.md` and `lessons-learned.md` first. Then inspect:

1. the user's feature request or `/create issue` command;
2. `specifications.md`;
3. `active-context.md`;
4. `architecture.md`;
5. `scenarios.md` and relevant existing feature issue documents;
6. relevant source, tests, configuration, and README files.

Check `git status` before editing and preserve unrelated worktree changes.

Prepare a concise analysis covering:

- the problem and desired outcome;
- current behavior and relevant implementation;
- bounded context and affected components;
- applicable existing scenario or need for a new scenario;
- functional and non-functional requirements;
- architecture, persistence, integration, compatibility, and operational risks;
- a sequenced implementation plan.

Ask focused questions when status, priority, scope, compatibility, or behavior
is materially ambiguous. If status is not provided, propose `Draft`. Do not
invent a priority. Do not mark a feature `In-progress` while merely drafting
its specification.

If the user has not already approved a concrete plan, present the analysis and
proposed plan for review before editing the memory-bank feature documents.

### 2. Add or update the scenario

Read `.github/memory/scenarios.md` before editing it.

Add a scenario when the feature introduces a new user or operational flow.
Update an existing scenario when the feature changes a documented flow. Keep
the repository template exactly:

1. Gherkin `Given` / `When` / `Then` functional description;
2. high-level technical analysis;
3. acceptance criteria.

Document observable behavior and only supported or explicitly approved
interfaces. Include REST endpoints, MCP tools, jobs, events, and operational
triggers only when they exist or are part of the approved feature scope.
Maintain unique, stable scenario headings and anchors.

### 3. Add the feature specification and link the scenario

Read `.github/memory/specifications.md` before editing it. Add or update a
feature entry with:

- `## Feature: <clear feature title>`;
- `Status`;
- `Priority` when known;
- `Parent Specs`, normally linking to `architecture.md`;
- `Bounded Context`;
- concise overview and context;
- functional requirements with stable `FR-01`, `FR-02`, ... IDs;
- non-functional requirements, or an explicit `Not yet`;
- a sequenced high-level implementation plan;
- a `Resources` section linking to the scenario and feature issue document.

Behavior-driven features must link to their scenario using a repository-relative
anchor, for example:

```markdown
- [Scenario: Allocate structured new demand](./scenarios.md#scenario-2-allocate-structured-new-demand)
```

Keep the feature title, terminology, FR IDs, status, priority, and plan
synchronized with the issue document.

### 4. Create the feature issue document

Create `.github/memory/issues/feature-<short-kebab-case-title>.md` using an
existing issue document as the structural reference. Include:

- feature title, status, priority, context, and bounded context;
- the same FR/NFR requirements and implementation plan as `specifications.md`;
- a link to the scenario;
- the scenario's Gherkin description, high-level technical analysis, and
  acceptance criteria when the feature changes that flow;
- technical analysis of current code and configuration;
- affected components, ports, adapters, persistence, integrations, and tests;
- risks, compatibility concerns, and implementation acceptance criteria.

The issue document is an implementation handoff. It must remain synchronized
with the scenario and specification when either changes.

### 5. Update active context

Update `.github/memory/active-context.md` with:

- the feature title;
- links to the specification, scenario, and issue document;
- a concise analysis or implementation-progress summary;
- whether implementation has started;
- next steps and unresolved decisions.

Do not claim implementation has started when only documentation was prepared.
Do not copy stale feature history into a new feature entry.

### 6. Handoff for implementation

Before implementation begins, verify:

- the scenario uses the required three-part template;
- the feature exists in `specifications.md`;
- the scenario is linked from the specification;
- the issue document contains synchronized requirements and plan;
- `active-context.md` links the same documents;
- status and priority are consistent across the documents.

When implementation is explicitly started:

1. Re-read the linked scenario, specification, issue document, architecture,
   and active context.
2. Move the feature status to `In-progress` in `specifications.md`.
3. Update `active-context.md` with the implementation focus.
4. Implement the approved scope.
5. Synchronize documentation if supported behavior or architecture changes.

## Verification and handoff

For documentation-only feature preparation:

- verify links and Markdown anchors;
- verify synchronized FR/NFR IDs, status, priority, and plan;
- inspect the intended sections after editing;
- run `git diff --check`;
- do not run application tests unless documentation tests exist.

For implementation work, use the smallest relevant formatting, compilation,
and test commands, expanding validation when targeted results require it.

Report the paths of the updated specification, scenario, issue document, and
active context. State clearly whether the workflow prepared documentation only
or also implemented code.

## Invocation

In GitHub Copilot CLI, invoke this skill with the feature idea, for example:

```text
/feature-specification Automate fulfillment of open backorders after inbound supply becomes available.
```

If no detailed feature idea is provided, invoke `/feature-specification` and
collect the feature idea and any required status, priority, or scope decisions.
