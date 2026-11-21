# Health A4 Design Packet

**Owner:** Khanh · **Slice:** health lookup, intents, receipts, and history

## Decision

Use `JdbcClient` and Flyway in the Health service's owned PostgreSQL `health` schema. Keep clinical rules in the application/domain layer and call identity and policy through outbound ports. Do not read IAM or movement tables owned by another service.

## Data model

`farm` and `barn` provide canonical location; `cattle` keeps permanent RFID identity and the resource version; `cattle_custody` holds current farm, barn, and custody version. `vaccination_schedule` defines a planned dose. `health_intent` binds an immutable request ID to its canonical payload hash and outcome. `clinical_record` is append-only and versioned. `operation_receipt` stores the durable response. `source_evidence` and `health_audit_outbox` capture the outcome in the same transaction.

## Retry and concurrency rules

- SHA-256 covers the canonical request; object keys in the clinical payload are sorted before hashing.
- `intent_id` is the primary key. `INSERT ... ON CONFLICT DO NOTHING RETURNING` lets one concurrent request claim the ID without aborting the losing transaction.
- Same ID and hash returns the stored receipt. Same ID with a different hash returns 409 after current `READ_RECEIPT` authorization.
- New submissions lock cattle and custody rows, refresh current authority, and compare `expectedVersion`. Every canonical custody update must advance that aggregate resource version; `custody_version` is also supplied to policy as metadata. A stale resource or custody snapshot is saved as `NEEDS_REVIEW`; it creates no clinical record.

## Dose, correction, and transaction rules

`schedule_completion.schedule_id` is unique, so one scheduled dose has one effective record. A repeat dose is rejected with a durable receipt. Corrections append a new record linked to the record corrected, preserve the first acceptance's `origin_farm_id`, and move the schedule's effective-record pointer without editing old clinical rows.

The transaction commits the intent outcome, accepted clinical row when applicable, receipt, source evidence, and outbox event together. Failure in evidence or outbox persistence rolls back the clinical write and receipt.

## Authorization boundary

Use `VIEW_CATTLE` for identity lookup, `SUBMIT_HEALTH` for a new write, `READ_HISTORY` for each row, and `READ_RECEIPT` for every receipt resolution. Accepted receipts carry their real record and immutable origin; a receipt without an accepted record uses current target scope only. Phuc's policy adapter is integrated. Phu's verified-principal and current-grant providers are still an integration prerequisite; the optional synthetic grant fixture is disabled by default and is not a production authority source.

**Handoff status:** packet prepared locally in the repository. The access-policy owner is identified in the contract adapter; no external review message was sent from this workspace.
