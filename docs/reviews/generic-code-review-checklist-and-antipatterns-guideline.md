# Code Review Checklist and Anti-Pattern Catalog (Guideline)

This guideline follows the structure and intent of:
- https://hidekazu-konishi.com/entry/code_review_checklist_and_antipatterns.html

It is adapted as a practical reviewer reference for day-to-day PR reviews.

## Table of Contents

1. [What Code Review Is For (and What It Isn't)](#1-what-code-review-is-for-and-what-it-isnt)
2. [The Reviewer Mental Model — Speed vs Depth Trade-Off](#2-the-reviewer-mental-model--speed-vs-depth-trade-off)
3. [Checklist 1: Bug Risk Indicators](#3-checklist-1-bug-risk-indicators)
4. [Checklist 2: Readability and Naming](#4-checklist-2-readability-and-naming)
5. [Checklist 3: Performance and Resource Usage](#5-checklist-3-performance-and-resource-usage)
6. [Checklist 4: Security](#6-checklist-4-security)
7. [Checklist 5: Test Coverage and Quality](#7-checklist-5-test-coverage-and-quality)
8. [Anti-Pattern Catalog](#8-anti-pattern-catalog)
9. [Reviewing AI-Generated Code](#9-reviewing-ai-generated-code)
10. [Review Workflow Tips](#10-review-workflow-tips)
11. [Summary](#11-summary)
12. [References](#12-references)

---

## 1. What Code Review Is For (and What It Isn't)

### 1.1 The four core goals
- **Verify correctness against stated intent** (not just compile/test pass).
- **Apply design pressure early** before code becomes hard to change.
- **Spread knowledge** across the team through review feedback.
- **Establish shared ownership** of merged code.

### 1.2 What review is not
- Not style-policing when tooling can enforce style.
- Not a forum for personal taste.
- Not a replacement for static analysis and tests.
- Not a ritual gate that slows work without improving quality.

### 1.3 Reviewer posture
- Read every changed line with the PR intent in mind.
- Ask questions before prescribing.
- Mark polish-only comments as `Nit:`.
- Prioritize correctness, maintainability, and risk reduction over personal preferences.

---

## 2. The Reviewer Mental Model — Speed vs Depth Trade-Off

### 2.1 First-pass triage (fast pass)
Answer quickly:
- What is this change trying to do?
- What is the blast radius?
- What is the test surface?
- What review depth is needed?

If first-pass triage is hard, ask for PR split before deep review.

### 2.2 Deep review (axis-by-axis)
Review in focused passes rather than everything at once:
1. Bug risk
2. Readability/naming
3. Performance/resources
4. Security
5. Tests

Apply all five for high-risk paths; fewer for low-risk mechanical changes.

### 2.3 Decision rules
- **Approve**: no required action items.
- **Approve with comments**: non-blocking improvements.
- **Request changes**: at least one must-fix issue.

Keep blocker comments explicit and scoped.

---

## 3. Checklist 1: Bug Risk Indicators

This is usually the highest-value pass and often the best one to do first. It is also the pass where system familiarity matters most: reviewers who understand the domain can spot bugs hidden in otherwise clean-looking diffs. Treat this checklist as language-agnostic core checks, then add stack-specific checks.

### 3.1 Null, undefined, and default-value paths
- Inputs that may be missing (`null`, `undefined`, `None`, `nil`, empty optional) are rejected or normalized before downstream assumptions.
- Default argument values do not introduce surprising behavior changes for callers.
- “Missing value” is not accidentally treated as “empty value” when those semantics differ.
- Optional-returning functions have tests for both presence and absence branches.

### 3.2 Off-by-one and boundary conditions
- Loop bounds are intentionally inclusive/exclusive and easy to explain.
- Pagination semantics match repository conventions (zero/one based, closed/half-open intervals).
- Empty input (`[]`, `""`, `0`) and minimal input behave intentionally.
- Unicode-sensitive logic does not assume byte count equals character count.

### 3.3 Time, timezone, and daylight savings
- Stored/transmitted timestamps are UTC unless there is a documented exception.
- User-facing times are rendered in user locale/timezone, not server defaults.
- Date/time arithmetic uses calendar-aware libraries rather than raw second math.
- Tests depending on current time use injected clocks to avoid midnight/DST flakiness.

### 3.4 State transitions and invariants
- State has a single source of truth (avoid multiple booleans that can drift).
- Valid transitions are explicit; undefined transitions are rejected.
- Invariants are enforced at construction/boundary and not just implied.
- Check-then-act sequences that rely on invariants are atomic where needed.

### 3.5 Resource lifecycle
- Ownership is explicit for files, sockets, DB handles, transactions, locks, and workers.
- Cleanup is guaranteed via structured mechanisms (`finally`, `defer`, scoped resource APIs).
- Repeated allocations in loops are released promptly or justified.
- Background jobs/workers have explicit shutdown behavior.

### 3.6 Concurrency and TOCTOU
- Shared mutable state is synchronized, single-owner, or made immutable.
- Time-of-check/time-of-use windows are minimized and not split by unrelated I/O.
- Read-modify-write flows use transactions, compare-and-swap, or lock-scoped atomicity.
- Cancellation/timeout signals are propagated through all long-running paths.

### 3.7 Error handling
- Errors are surfaced intentionally; silent swallowing is avoided.
- Boundary errors (network/disk/external APIs) are translated into domain-level failures.
- Retries use bounded attempts with backoff and clear stop criteria.
- Logs include enough context for debugging without leaking secrets.

### 3.8 Data integrity across layers
- Database constraints align with application assumptions (`NOT NULL`, FK, uniqueness).
- Migrations are reversible where practical; irreversible changes are explicitly documented.
- External data is validated at ingress boundaries, not deep inside core logic.
- Serialization/deserialization paths preserve required information and types.

---

## 4. Checklist 2: Readability and Naming

Readability is an operational concern, not cosmetic polish. If a careful reviewer struggles to understand the change, on-call engineers under pressure will struggle even more. This checklist is intentionally practical and focused on comprehension.

### 4.1 Naming
- Names communicate role and domain meaning, not just storage type.
- Boolean names read as true/false questions (`is`, `has`, `can`, `should`).
- Function names use verbs matching side effects (`fetch`, `apply`, `enqueue`, `commit`).
- Names match abstraction level (avoid broad names for narrow behaviors and vice versa).

### 4.2 Function size and cohesion
- Functions have one clear responsibility and can be summarized in one sentence.
- High parameter counts are treated as a design smell and reviewed for decomposition.
- Long functions are split into intent-revealing helpers where possible.
- Extracted helpers improve clarity rather than merely moving code around.

### 4.3 Comments and intent
- Comments explain rationale, constraints, trade-offs, and non-obvious choices.
- Comments that only restate code are removed or code is simplified.
- Workaround comments include owner and tracking reference.
- Critical “why” context lives close to the code path it explains.

### 4.4 Cohesion and coupling
- Code that changes for the same reason stays together; unrelated concerns are separated.
- Public API surface stays minimal and intentional.
- Cross-layer communication uses stable interfaces where layering matters.
- Cyclic dependencies are avoided or explicitly justified.

### 4.5 Six-month maintainability check
Before approval, check whether a future maintainer could quickly answer:
- Why this branch or guard exists.
- Why specific constants or thresholds were chosen.
- Why this error is handled here (and not elsewhere).
- Why this dependency or abstraction is necessary.

---

## 5. Checklist 3: Performance and Resource Usage

This pass is not about micro-optimizing everything. It is about catching failure patterns that are fine in tests and small environments, but become incidents under real load.

### 5.1 Database access patterns
- No accidental N+1 behavior in loops over records or relationships.
- Query predicates on large tables align with actual indexes.
- Pagination strategy matches scale requirements (cursor/keyset when offset becomes expensive).
- Transactions are bounded and do not include unrelated network calls.

### 5.2 Memory and allocation behavior
- Hot paths avoid avoidable per-call allocations.
- Large payloads are streamed when practical instead of fully buffered.
- Caches have explicit bounds (TTL, size limit, eviction policy).
- Collections are pre-sized when cardinality is known.

### 5.3 Sync vs async and execution model
- Blocking operations do not run on event-loop or latency-critical threads.
- Long-running operations have timeout + cancellation behavior.
- Parallelism is bounded (pool/semaphore/worker model) rather than unbounded fan-out.
- Backpressure exists so producers cannot overwhelm consumers indefinitely.

### 5.4 Network interactions
- External calls have explicit connect/read/overall timeout budgets.
- Retry is finite, backoff-based, and scoped to retryable errors.
- Idempotent operations use idempotency semantics/keys where needed.
- Circuit-breaking/degradation strategy exists for repeated dependency failures.

### 5.5 100x load thought experiment
Ask explicitly: “What changes at 100x traffic or data size?”
- Any O(N²) or fan-out behavior triggered by larger inputs?
- Any memory accumulation turning into pressure/GC churn?
- Any external dependency rate limits likely to trip?
- Any logging/metrics volume likely to become operationally expensive?

---

## 6. Checklist 4: Security

Security review should be explicit and conservative. Missed issues here usually have high impact, and early escalation is cheap compared to late remediation.

### 6.1 Input validation at trust boundaries
- Treat user input, file input, environment input, and external service input as untrusted.
- Prefer allowlist validation where possible; use explicit size/length limits.
- Use robust libraries for parsing security-sensitive formats.
- Ensure validation occurs before business logic consumes values.

### 6.2 Output encoding and injection resistance
- Apply context-specific encoding at output boundaries (HTML/JS/SQL/shell/etc.).
- Keep auto-escaping enabled in templating where applicable.
- Ensure logs redact tokens, credentials, and sensitive personal data.
- Prevent user-controlled download metadata from creating injection vectors.

### 6.3 Authentication and authorization
- Authn and authz are treated as separate checks and both are present where required.
- Authorization is server-side and includes object-level checks.
- Privileged operations require explicit permission gates.
- Client-side checks are never trusted as security controls.

### 6.4 Secrets and credential management
- No secrets in code, fixtures, screenshots, docs, or committed config.
- Runtime secret retrieval uses approved secret stores/rotation paths.
- Secret scanning exists in pre-commit or CI workflows.
- Rotation and rollover are operationally feasible.

### 6.5 Dependency and supply-chain risk
- Lockfile changes are reviewed as security-sensitive changes.
- New packages are validated for authenticity/maintenance/reputation.
- Typosquatting/slopsquatting risk is considered for unfamiliar package names.
- Dependency advisory tooling runs in CI with clear remediation policy.

### 6.6 Logging and auditing
- Security-sensitive actions leave an audit trail with correlation identifiers.
- Logs avoid storing high-risk secrets or full credentials.
- Auth decision logs are sufficient for incident investigation.
- Logging policy balances traceability and data-minimization.

### 6.7 Attacker-mindset prompts
Review with adversarial prompts:
- What if input is oversized, malformed, or delimiter-rich?
- What if caller is authenticated but unauthorized for this resource?
- What if same request is replayed at high frequency?
- What if dependency returns syntactically valid but semantically malicious data?

---

## 7. Checklist 5: Test Coverage and Quality

Coverage percentage alone is not the goal. Review whether tests produce confidence in behavior under realistic and adverse conditions.

### 7.1 Behavior over implementation detail
- Tests assert externally observable outcomes, not only internal call paths.
- A behavior-preserving refactor should not break most tests.
- Assertions are specific and meaningful (not merely “no exception thrown”).
- Mocks are used sparingly and intentionally at true boundaries.

### 7.2 Right test mix
- Unit tests cover core logic quickly and deterministically.
- Integration tests verify boundary seams (handler/service/repo/db/message bus).
- End-to-end tests cover at least key user-visible flows.
- Property/invariant-style tests are used for non-trivial rules where valuable.

### 7.3 Test hygiene
- Failures are diagnosable from test output.
- Tests are independent and order-insensitive.
- Synchronization is deterministic (avoid arbitrary sleeps where possible).
- Flaky tests are fixed/quarantined explicitly, not normalized as noise.

### 7.4 Boundary and error-path coverage
- Validation rejects include negative-path tests.
- Error-handling branches are covered where risk is significant.
- Public contracts are tested for success, expected failures, and edge cases.
- Regression tests reference concrete bug/incident context when applicable.

### 7.5 Contract tests for external and AI-drafted integrations
- Verify external API usage against real or controlled contract sources.
- Ensure generated/inferred clients match real method names and payload shapes.
- Detect drift between mocked and real integrations early.
- Prioritize contract tests when code was AI-generated or rapidly produced.

---

## 8. Anti-Pattern Catalog

Use this catalog for consistent reviewer comments. For each anti-pattern, classify findings as:
- **Symptom** (what appears in the diff),
- **How to detect** (what to inspect or measure),
- **Suggested fix** (what should change before or after merge).

1. **God Function**
   - Symptom: one function carries multiple responsibilities and control paths.
   - How to detect: high line count, many branches/arguments, reviewer cannot summarize intent.
   - Suggested fix: split into intent-revealing stages with single-purpose helpers.

2. **Boolean Parameter Trap**
   - Symptom: calls like `fn(x, true, false)` hide semantics.
   - How to detect: positional boolean arguments, especially more than one.
   - Suggested fix: explicit option objects/enums or separate functions for distinct behaviors.

3. **Premature Abstraction**
   - Symptom: abstraction introduced before a real second use case.
   - How to detect: interfaces/factories/base classes with a single implementation.
   - Suggested fix: simplify now; re-introduce abstraction when real variability appears.

4. **Defensive Programming Overkill**
   - Symptom: internal code repeatedly validates already-trusted values.
   - How to detect: layered duplicate guards with low incremental safety value.
   - Suggested fix: centralize validation at trust boundaries; keep internals focused.

5. **Silent Exception Swallow**
   - Symptom: errors disappear without handling, logging, or propagation.
   - How to detect: empty catches, broad catch-all with no action.
   - Suggested fix: handle specific exceptions intentionally or propagate with context.

6. **Magic Numbers**
   - Symptom: unexplained literals drive control logic.
   - How to detect: repeated numeric literals in conditions/timeouts/status logic.
   - Suggested fix: replace with named constants and document selection rationale when non-obvious.

7. **Comment-Only Workaround**
   - Symptom: “TODO/FIXME/HACK” comments with no owner/plan.
   - How to detect: issue tags lacking ticket reference/owner/date.
   - Suggested fix: resolve now or attach explicit ownership and tracking item.

8. **Concrete-Type Layer Coupling**
   - Symptom: upper layers depend directly on infrastructure concrete classes.
   - How to detect: domain/application imports concrete DB/network adapters.
   - Suggested fix: depend on boundary interfaces; bind concrete types at composition layer.

9. **TOCTOU (Time-of-check / Time-of-use)**
   - Symptom: validity check and action are separated by race window.
   - How to detect: check-then-act sequences on mutable shared resources.
   - Suggested fix: atomic operations, transactions, locks, or explicit race-aware fallback logic.

10. **Test Theater**
   - Symptom: tests are elaborate but verify little user-visible behavior.
   - How to detect: heavy mock setup, assertions mainly on call interactions.
   - Suggested fix: assert outputs/state transitions/observable side effects.

11. **Hidden State Mutation**
   - Symptom: pure-looking APIs mutate state unexpectedly.
   - How to detect: read-style method names with hidden writes/caches/mutations.
   - Suggested fix: make side effects explicit in naming or remove side effects.

12. **Copy-Paste with Subtle Drift**
   - Symptom: near-duplicate code blocks diverge over time.
   - How to detect: duplicated regions with tiny inconsistent edits.
   - Suggested fix: extract common logic; parameterize intentional differences.

---

## 9. Reviewing AI-Generated Code

All core checklists still apply; add AI-specific scrutiny:

### 9.1 Hallucinated APIs and slopsquatted packages
- Verify every import/package/method against real docs/registries.
- Treat lockfile additions as supply-chain risk.
- Use contract tests for external API calls claimed by generated code.

### 9.2 Plausible-but-wrong implementations
- Code may look idiomatic but violate exact business intent.
- Review against requirement text, not stylistic plausibility.

### 9.3 Over-abstraction tendency
- AI often introduces generic abstractions too early.
- Ask for simpler concrete implementation unless second use case exists.

### 9.4 Missing rationale
- Non-obvious design choices often lack “why”.
- Require rationale in code comments, commit, or PR description.

### 9.5 Mass-produced boilerplate
- Large repetitive generated blocks increase reviewer fatigue and defect risk.
- Collapse into helpers/table-driven tests/centralized validation where possible.

### 9.6 AI-specific quick checklist
- Existence check: package + method + signature.
- Intent check: code matches PR claim exactly.
- Simplicity check: no speculative abstractions.
- Rationale check: non-obvious choices documented.
- Duplication check: repetitive blocks collapsed.
- Contract check: external integration is contract-tested.
- Human accountability check: security-critical paths reviewed by qualified human.

---

## 10. Review Workflow Tips

- Classify comments as **Block / Suggest / Nit**.
- Keep PRs reviewable in one focused sitting; split mixed concerns.
- Use async etiquette: clear response-time expectations, self-review notes, thread resolution discipline.
- Let automation handle mechanical checks first; reserve human attention for design/risk judgment.
- Always self-review before requesting review.

---

## 11. Summary

- Review for behavior and risk, not taste.
- Match review depth to change risk.
- Use explicit checklists for bug risk, readability, performance, security, and tests.
- Track recurring anti-patterns explicitly.
- For AI-generated diffs, increase verification at dependency/API/rationale boundaries.

---

## 12. References

Primary source article:
- https://hidekazu-konishi.com/entry/code_review_checklist_and_antipatterns.html

References followed from the article:
- Google Engineering Practices — Overview: https://google.github.io/eng-practices/review/
- Google Engineering Practices — Standard of Code Review: https://google.github.io/eng-practices/review/reviewer/standard.html
- Google Engineering Practices — What to look for: https://google.github.io/eng-practices/review/reviewer/looking-for.html
- OWASP Code Review Guide project page: https://owasp.org/www-project-code-review-guide/
- OWASP Code Review Guide repository: https://github.com/OWASP/www-project-code-review-guide
- Microsoft Engineering Fundamentals Playbook — Code Reviews: https://microsoft.github.io/code-with-engineering-playbook/code-reviews/
- Microsoft Engineering Fundamentals Playbook — Reviewer Guidance: https://microsoft.github.io/code-with-engineering-playbook/code-reviews/process-guidance/reviewer-guidance/
- NIST SP 800-218 (SSDF): https://csrc.nist.gov/pubs/sp/800/218/final
- Spracklen et al., package hallucinations (arXiv): https://arxiv.org/abs/2406.10279
- AI-era engineering role shift: https://hidekazu-konishi.com/entry/ai_era_software_engineer_paradigm_shift.html
- Claude Code getting started: https://hidekazu-konishi.com/entry/claude_code_getting_started_guide.html
- AWS WAF prompt-injection patterns: https://hidekazu-konishi.com/entry/aws_waf_generative_ai_prompt_injection_patterns.html
