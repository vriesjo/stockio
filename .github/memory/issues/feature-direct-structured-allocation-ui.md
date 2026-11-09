# Feature: Add a direct structured allocation UI alongside the agentic page

- **Status:** In-progress
- **Priority:** Not set
- **Bounded Context:** Frontend Integration & Allocation API Workflows
- **Implementation started:** Yes. Direct page, route/client, and tests are in progress.
- **Specification:** [Feature specification](../../../docs/specifications.md#feature-add-a-direct-structured-allocation-ui-alongside-the-agentic-page)
- **Parent Specs:** [Architecture](../../../docs/architecture.md), [Feature: Replace the frontend MCP bridge with an Axios service client](../../../docs/specifications.md#feature-replace-the-frontend-mcp-bridge-with-an-axios-service-client)
- **Scenario:** [Scenario 20](../../../docs/scenarios.md#scenario-20-submit-structured-allocation-requests-from-a-direct-ui-workflow)

## Context and scope

The current page (`stockio-api/src/main/kotlin/nl/stockio/ui/app/page.tsx`)
supports an agentic workflow: user provides a product phrase, frontend submits
to a same-origin route, and the service resolves the phrase before allocation.

The requested extension is a second, very similar page for **direct structured
allocation input**:
- SKU (instead of product phrase),
- quantity and priority,
- order metadata (order ID, customer, placed date, required delivery date, as-of date),
- request identity + JSON preview,
- submit through Axios (same transport style as existing client).

The existing agentic page remains in scope to preserve, not replace.

## Contract source of truth

This feature uses the allocation jobs contract directly:

- **Endpoint contract:** `POST /api/allocation/jobs`
- **OpenAPI spec:** `stockio-api/src/main/resources/openapi/allocation-controller.yaml`
- **Request DTO:** `stockio-api/src/main/kotlin/nl/stockio/api/dto/AllocationRequest.kt`

The direct UI payload and preview must align with this contract (`AllocationRequest`
including `runType`, `demandOrders`, and `inboundShipments`).

## Functional Requirements (FR)

| ID | Title | Description |
| --- | --- | --- |
| FR-01 | Dedicated direct page | Add a new page/route for direct structured allocation while keeping the current agentic page unchanged and navigable. |
| FR-02 | Form inputs | Provide fields for SKU, quantity, priority, order ID, customer, placed date, required delivery date, and as-of date. |
| FR-03 | Simple navbar | Use a simple default Tailwind navbar to switch between flows and preserve page orientation. |
| FR-04 | Request identity behavior | Display request identity and preserve/reset behavior consistent with current retry semantics. |
| FR-05 | JSON preview | Show exact outbound JSON payload before submit, with validation feedback if input is incomplete/invalid. |
| FR-06 | Server-side Axios submission | Reuse Next.js server-side Axios pattern for same-origin browser calls, including explicit timeout and transport error handling. |
| FR-07 | Contract alignment | Ensure route/client/types/tests/docs align to `POST /api/allocation/jobs` with `AllocationRequest` from the shared stockio-api module. |

## Non-Functional Requirements (NFR)

| ID | Title | Description |
| --- | --- | --- |
| NFR-01 | Preserve existing UX | Existing product-resolution flow and behavior remain unchanged unless explicitly approved. |
| NFR-02 | Honest outcomes | UI messaging must reflect returned API status/body and not overstate completion guarantees. |
| NFR-03 | Testable boundaries | Add focused tests for payload shape, request identity lifecycle, and error mapping for the direct flow. |

## Implementation plan

1. Use `allocation-controller.yaml` + `AllocationRequest` as the direct page contract baseline and keep route naming explicit.
2. Add direct-flow request model/builder and Axios route/client implementation.
3. Implement direct page UI and navbar entry with request identity and JSON preview.
4. Add focused unit/route/browser checks for validation, payload shape, and error handling.
5. Update scenario/specification/architecture/README/active-context links and wording.

## Functional scenario

### Gherkin description
**Given** the existing agentic allocation page and the new direct structured allocation page \
**When** a user enters direct order/SKU fields and submits \
**Then** the frontend sends the selected contract payload through a same-origin Axios path \
**And** the user sees request identity, JSON preview, and explicit success/error outcomes

### Acceptance Criteria

- New page route is available and navigable from a simple navbar.
- Form captures all requested direct fields and validates required values.
- Request identity and JSON preview are visible and update with defined retry/edit behavior.
- Submit path uses server-side Axios and returns explicit HTTP/domain error handling.
- Endpoint/payload decision is documented and reflected consistently in implementation and tests.
