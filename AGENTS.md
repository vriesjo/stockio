# AGENTS.md

These instructions define the repository workflow for planning, documenting, and
implementing Stockio changes. They apply to interactive sessions and assigned
issues.

## Repository memory

Use the following files as the source of truth for feature work:

| File                                                                                 | Purpose                                                                                           |
|--------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| [`spec-driven-design.md`](.github/memory/spec-driven-design.md)                      | Spec-Driven-Development process.                                                                  |
| [`docs/architecture.md`](docs/architecture.md)                                       | Current system architecture, technologies, and design boundaries.                                 |
| [`docs/scenarios.md`](docs/scenarios.md)                                             | Supported user and operational flows in Gherkin, with technical analysis and acceptance criteria. |
| [`docs/specifications.md`](docs/specifications.md)                                   | Feature specifications and links between features, scenarios, and architectural baselines.        |
| [`.github/memory/active-context.md`](.github/memory/active-context.md)               | Current work-in-progress and handoff context.                                                     |
| [`.github/memory/issues/`](.github/memory/issues/)                                   | Feature implementation documents containing synchronized requirements and technical analysis.     |
| [`.github/memory/tasks/lessons-learned.md`](.github/memory/tasks/lessons-learned.md) | Reusable process lessons discovered during repository work.                                       |

The active feature-specification file is
`docs/specifications.md`; use that filename consistently in new
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

## Spec-Driven-Development lifecycle
- The flow is defined here: [spec-driven-design.md](.github/memory/spec-driven-design.md)
- In short:  
  - Plan together with the user
  - User reviews & approves.
  - Deep Research by agent.
  - Define feature agent.
  - User approves only if the specification is nicely linked to the feature, scenario.
  - Implement feature locally by agent.
  - User reviews & approves.
The lifecycle above is initiated when the user invokes `/plan ..` or `/create plan..`.

### 1 Write the plan

Based on the initial in context analysis:

- The user's `/plan ..` or `/create plan ..` command.
- The current repository code, `Repository memory` see above.
- Take a spec-driven look at relevant existing entries in `scenarios.md` and `specifications.md`.
- Peek into `lessons-learned.md` before making assumptions.

Identify the problem, bounded context, current behavior, desired behavior,
affected components, risks, dependencies, and open decisions. Ask focused
questions when status, priority, scope, compatibility, or behavior is materially
ambiguous. Layout the plan and store it here `.cursor/plans`. Bared minimal layout contains: 
- current behavior, target behavior, bounded context, steps, verification, risks.

### 2 Write the feature 

Based on the plan, write the complete feature implementation. This feature implementation is the result of more deep research and analysis. 
It is not a draft, but a complete feature implementation. The feature implementation is stored in `.github/memory/issues`. 
The feature implementation contains at least the following sections:
- Feature title, status, priority, context, and bounded context.
- Functional requirements with stable `FR-01`, `FR-02`, ... IDs.
- Non-functional requirements, or an explicit `Not yet` when none are defined.
- Application contract (e.g. rest, broker) for the feature, if applicable.
- A sequenced high-level implementation plan.
- The Gherkin style scenarios.
- Acceptance criteria.

### 3. Add or update the functional scenario

Read the scenario structure in
[`scenarios.md`](docs/scenarios.md) before editing it.

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

### 4. Add or update the specification and link to the feature and scenario resources

Read the established structure in
[`specifications.md`](docs/specifications.md) before editing it.

Add or update a feature entry containing:

- `## Feature: <clear title>`;
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

1. Re-read the linked scenario, specification, issue document, architecture, and
   active context.
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

# Code Review Guidelines

When reviewing pull requests, strictly follow all architectural, coding, and testing guidelines defined here:

1. [generic-code-review-checklist-and-antipatterns-guideline.md](docs/reviews/generic-code-review-checklist-and-antipatterns-guideline.md)
2. [kotlin+eda+outbox_code_review_guidelines.md](docs/reviews/kotlin%2Beda%2Boutbox_code_review_guidelines.md)
3. [spring-boot-code-review-guideline.md](docs/reviews/spring-boot-code-review-guideline.md)
4. [security-code-review-guideline.md](docs/reviews/security-code-review-guideline.md)
