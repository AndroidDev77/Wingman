# 0005: Connector capability model

- Status: Accepted (WING-002, 2026-11-01)

## Context
Wingman targets transports with very different abilities: real SMS, Android notifications, a possible iMessage companion, and dating apps that have no supported API. Overstating abilities is the main product risk. Examples are claiming a send happened when only notification text was read, or swiping without support. AGENT_RULES requires capabilities to be advertised separately and labeled VERIFIED, UNVERIFIED or UNSUPPORTED.

## Decision
- `contracts/schemas/connector_capabilities.v1.json` requires **all five** capabilities, `read`, `draft`, `send`, `swipe` and `calendar_write`, to be stated explicitly. Each one carries `status`: `VERIFIED`, `UNVERIFIED` or `UNSUPPORTED`. A capability cannot be omitted.
- Capabilities are **independent**. None implies another. Notification access maps to at most `read`. `send` for a notification connector is `UNSUPPORTED` unless an app's RemoteInput reply has actually been tested.
- `VERIFIED` requires `evidence` (where the reproduction is recorded) and `verified_at`. The mock connector may be `VERIFIED` against its deterministic simulator, and its notes say it is simulated. Platform `mock` events use separate `mock.*` event types, so simulated success can never be recorded as a real-platform event.
- Policy use:
  - `UNSUPPORTED`: the operation returns a typed error (`capability_unsupported`). The UI shows the platform or feature as unavailable.
  - `UNVERIFIED` send: allowed only through explicit user approval (`ApprovalReason: capability_unverified`), never on autopilot.
  - `VERIFIED` send: eligible for autopilot, subject to the rest of the policy.
  - `reports_delivery_receipts` on `send` decides whether `delivered` can ever be reached. Without receipts, messages stop at `sent`.
- No v1 HTTP endpoint requires `swipe`, `draft` (platform compose) or `calendar_write`.

## Consequences
- WING-006 implements `ConnectorCapabilities` exactly as this schema defines it, and returns typed unsupported results.
- WING-016 and WING-017 publish `UNVERIFIED` until a physical-device test is recorded.
- Capability changes are emitted as `connection.capabilities_changed` events and stored per connection.
