# Active Context

## Feature: Automate backorder fulfillment after physical inbound receipt

- **Specification:** `.github/memory/specifications.md#feature-automate-backorder-fulfillment-after-physical-inbound-receipt`
- **Scenario:** `.github/memory/scenarios.md#scenario-4-fulfill-open-backorders`
- **Issue:** `.github/memory/issues/feature_automate-backorder-fulfillment-after-inbound-receipt.md`

### Steps worked on
- Captured the approved design direction: expected inbound registration does not trigger fulfillment; physical receipt during stock-ledger synchronization does.
- Defined a dedicated `BackorderFulfillmentCommand` and reusable application service instead of reusing `AllocationRequest` or the `NEW_DEMAND` flow.
- Documented post-commit JobRunr scheduling, affected-SKU scoping, priority/FIFO behavior, expiry filtering, projection refresh, idempotency, and concurrent-execution protection.
- Added the feature specification and synchronized Scenario 4 with the feature issue document.
- Moved the feature to In-progress and implemented the dedicated backorder fulfillment command, reusable service, JobRunr job, and manual MCP adapter path.
- Extended stock-ledger synchronization to enqueue scoped fulfillment after a successful transaction commit, with stable event/job identities and existing SKU-level locking/idempotency.
- Added shared projection refresh for manual and automatic fulfillment while preserving separation from the `NEW_DEMAND` allocation path.

### Next possible steps (suggestions)
- Add focused integration coverage for post-commit enqueueing, rollback suppression, scoped receipt fulfillment, and duplicate receipt events.
- Review operational logging and JobRunr dashboard behavior for enqueue failures and retry outcomes.

Tell us what is the current active context for the agent. This should include:
- Reference to the feature definition in `.github/memory/requirements-specification.md` in the `## Feature` section.
- Steps worked on, including brief summary of the AGENTS.md `Workflow orchestration` routine in the `### Steps worked on` section.
- If you have any suggestions for next steps, add them in the `### Next possible steps (suggestions)` section.
