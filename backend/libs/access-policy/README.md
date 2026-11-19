# access-policy

Pure Java 21 clinical authorization library. Coordinates:
`vn.edu.hcmut.vinabeef:access-policy:0.1.0-SNAPSHOT`.
No production dependencies, Spring runtime, HTTP, JWT, SQL, clock reads or audit persistence.
The repository parent supplies Java/JUnit/plugin management; this module builds a plain JAR.
Technical baseline chosen by Phúc under user authorization on 17 November 2026;
producer/consumer review and endpoint integration are still pending.

## Calling contract

`new ClinicalAuthorizationPolicy().evaluate(Action, Subject, ResourceContext, Instant)`
returns immutable `Decision(allowed, reason, matchedGrant)`. Public types are nested in
`vn.edu.hcmut.vinabeef.policy.ClinicalAuthorizationPolicy`.

| Action | Required resource type | Entitlement |
|---|---|---|
| VIEW_CATTLE | CattleResource | Current BS barn or FM farm; individual lookup only |
| SUBMIT_HEALTH | CattleResource | Current BS barn or FM farm; permission is only one acceptance precondition |
| READ_HISTORY | HistoryRecord | Current BS barn/FM farm, or FM farm matching that record's immutable origin |
| READ_RECEIPT | AcceptedReceipt | Same per-record entitlement as history |
| READ_RECEIPT | TargetReceipt | Current BS barn/FM farm only; pending/failed receipt without accepted record |

Other action/type pairs return DENY / ACTION_CONTEXT_MISMATCH even if the subject
would otherwise have current scope. A bare cattle context cannot authorize a history
collection. Every history row, count, link and receipt projection needs Health enforcement.
An accepted receipt is bound to its canonical record and that record's cattle; a
pending/failed receipt must not be promoted to AcceptedReceipt merely to gain origin access.

Each ACTIVE grant must match the entire role/action/farm/barn rule itself and be
effective at server `decisionTime` in `[validFrom, validUntil)`. Null `validUntil`
means no fixed expiry, not cached or permanently trusted authority. Versions are
nonnegative opaque metadata, not proof of freshness or business concurrency checks.
BS requires BOTH farm equality and a listed current barn. Empty barn sets grant no
BS scope. FM uses its farm, independent of its barn set. Farm/barn membership must
already have been verified by the adapter; this library cannot query membership.

Grants are additive, never spliced: SA/EM-only subjects have no ordinary clinical
permission. An independent matching BS/FM grant can allow; revoked/disabled/expired
grants neither add scope nor veto another valid grant. The model accepts farm-scoped
grants only (farmId required for every role). An adapter may omit global technical
grants from this clinical projection; it must not invent a farm or discard any relevant
clinical grant. Duplicate grant references are rejected rather than arbitrarily choosing
a current version. Opaque identifiers are compared exactly, without parsing tag/device IDs.

If multiple grants allow, select the lexicographically smallest grant reference using
Java String.compareTo, independent of input order. Within one FM grant, CURRENT_FARM
takes precedence over OWN_ORIGIN_RECORD. Allow carries `GrantReference(reference, version)`;
deny carries only NO_MATCHING_GRANT or ACTION_CONTEXT_MISMATCH with no matched grant,
subject identifier, resource identifier or clinical narrative. Public exception messages
describe invalid fields without embedding input values. Public HTTP mapping remains a
boundary responsibility; do not disclose protected existence via error differences.

## Compile-checked example

Imports: `java.time.Instant`, `java.util.List`, `java.util.Set`,
`vn.edu.hcmut.vinabeef.policy.ClinicalAuthorizationPolicy` and static
`vn.edu.hcmut.vinabeef.policy.ClinicalAuthorizationPolicy.*`.
The following body is copied from `PolicyUsageTest.readOwnOriginAfterTransferExample`.
All fixture IDs and times below are examples, not an authority provider.

```java
Instant decisionTime = Instant.parse("2026-11-17T08:00:00Z"); // server-owned in production
Grant grant = new Grant("grant-fm-fa", Role.FM, "FA", Set.of(), GrantStatus.ACTIVE,
        Instant.parse("2026-11-01T00:00:00Z"), null, 3);
Subject subject = new Subject("verified-subject", List.of(grant));
CattleContext current = new CattleContext("CA", "FB", "BC", 8);
ClinicalRecord record = new ClinicalRecord("H-A", "CA", "FA");
ClinicalAuthorizationPolicy policy = new ClinicalAuthorizationPolicy();
Decision history = policy.evaluate(Action.READ_HISTORY, subject,
        new HistoryRecord(current, record), decisionTime);
Decision receipt = policy.evaluate(Action.READ_RECEIPT, subject,
        new AcceptedReceipt("receipt-H-A", current, record), decisionTime);
Decision newWrite = policy.evaluate(Action.SUBMIT_HEALTH, subject,
        new CattleResource(current), decisionTime);
Decision pendingReceipt = policy.evaluate(Action.READ_RECEIPT, subject,
        new TargetReceipt("pending-intent", current), decisionTime);
```

The test checks history/accepted receipt ALLOW with OWN_ORIGIN_RECORD and grant
reference/version; new write and target-only receipt DENY. Health must supply these
values from authoritative data before invoking policy. No adapter/endpoint is implemented.

Health can depend on this JAR using its existing reactor version:

```xml
<dependency>
  <groupId>vn.edu.hcmut.vinabeef</groupId>
  <artifactId>access-policy</artifactId>
  <version>${project.version}</version>
</dependency>
```

This is an integration instruction, not an existing dependency in herd-health.

## Validation and authority

All public mandatory nulls, blank/padded identifiers, null collection elements,
negative versions, duplicate grant references, invalid effective intervals and
record/cattle mismatches throw `IllegalArgumentException`. Collections are defensively
copied and unmodifiable. Only `validUntil` and denied `matchedGrant` may be null.
An empty subject grant list is valid and denies. Enum parsing of unknown external
labels must fail at the adapter; do not substitute a permitted role/action. Public
Decision construction also rejects inconsistent allow/reason/match combinations.

Construction proves shape and record-target identifier agreement, NOT that a subject
was authenticated, a grant was issued, a receipt really links that record, custody is
current, barns belong to a farm, or origin is canonical. There is no `trusted=true`
flag or provider stub that would establish these facts.

Caller obligations before EVERY protected operation:

1. Identity owner supplies authenticated subject and authoritative current grants;
   no positive permission cache/TTL grace. Authority unavailable/unknown means boundary
   fail closed/unavailable with zero disclosure/effect; do not fabricate positive input.
2. Health resolves cattle/current custody and record/receipt associations server-side,
   never from untrusted farm/barn/actor form fields. Record origin is the canonical
   current farm at first acceptance, immutable across correction. Captured device,
   reader farm, ONLINE/SYNC and client time are provenance, not record origin.
3. Pass server decision time. No backdating to capturedAt. Revalidate/serialize authority
   and custody with new acceptance; loading two snapshots does not solve distributed
   revocation/transfer races. Required ordering and evidence remain integration work.
4. Evaluate each permitted action/context, filter rows/totals/links, then enforce Health
   lifecycle, expected-version, idempotency/payload, dose/evidence/transaction guards.
   ALLOW is not an accepted clinical effect. Failure to save source evidence is not
   successful acceptance. Known deactivated cattle retain ordinary authorized identity
   and history reads; lifecycle is intentionally absent from this permission model.
5. Resolve an already committed intent via current READ_RECEIPT permission; do not
   apply the new-acceptance expected-version guard to its existing effect. A user with
   current own-origin receipt access can read while lacking SUBMIT_HEALTH on the cattle.
   Original actor/knowing intent ID is never an extra entitlement. No delegated submit,
   intent adoption or reassignment is provided; Health must reject those workflows.

Unknown tag resolution, mobile selection/confirmation/local drafts, raw tokens, payloads,
expected business versions, provenance and clinical lifecycle are not API inputs. Tests
of their authorization projection do not verify their boundary/domain/UI outcomes.

## Verification

From repository root (Docker + PowerShell 7, existing local `.env`):

```powershell
pwsh -NoLogo -NoProfile -File scripts/dev.ps1 mvn -B -pl libs/access-policy -am verify
pwsh -NoLogo -NoProfile -File scripts/dev.ps1 mvn -B verify
```

Tests cover independent RBAC controls, cross-grant scope mixing, time boundaries,
immutable/invalid public inputs, transfer/origin/receipt distinctions and deterministic
matched grants. `PolicyUsageTest` compiles the example. No unit result proves JWT,
freshness, endpoint enforcement, filtering, transaction ordering or mobile behavior.
