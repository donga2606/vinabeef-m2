# Clinical snapshot checker — verification only

Owner: Nguyễn Hữu Phúc, 2352938. Step 3A, 17 November 2026.
Small PowerShell 7 tool and hand-authored JSON oracle for correctness/evidence checks.
It reads two files and emits JSON to stdout; it never writes inputs, calls Health,
authenticates a user, queries a database or generates expected results from actual data.
This folder follows the repository's PowerShell tooling; no new runtime, Maven module,
schema-registry entry or accepted clinical API contract is introduced.

**Current status: synthetic checker controls only.** Health remains a skeleton without
business endpoints/exports. PASS means the supplied snapshot agrees with this oracle,
not that a clinical application/integration, permissions, audit transaction or UI passed.
The existing access-policy library/root POM are unchanged from Step 2C QA.

## Run from repository root

PowerShell 7 is already available on the current host (`pwsh`); no Docker stack needed.

```powershell
pwsh -NoLogo -NoProfile -File verification/clinical/Check-ClinicalSnapshot.ps1 -ExpectedPath verification/clinical/fixtures/expected.small.json -ActualPath verification/clinical/fixtures/actual.synthetic.json
pwsh -NoLogo -NoProfile -File verification/clinical/Test-ClinicalChecker.ps1
```

Checker exit codes: **0** PASS, **1** well-formed snapshot mismatches, **2** invalid/missing
input (including duplicate identities), **3** unexpected checker error. One JSON result
contains `formatVersion`, `status`, `fixtureId`, `dataKind`, `diagnostics` and, for valid
inputs, row counts. Diagnostics carry `category`, `field`, `correlation` IDs and either
expected/actual values or a validation message. It reports relevant projection values;
keep real exported verification artifacts access-controlled and free of tokens/narratives.
Argument binding or launching the wrong PowerShell is outside the script's JSON protocol.

Self-test exit 0 means **all controls behaved as expected**, including nonzero checker
exits for deliberate corruption. Its JSON includes each expected category/correlation,
actual exit and full diagnostics, plus input-hash preservation. Variants are created in
a unique OS temp directory and only its recorded files/empty directory are removed.
Canonical fixtures are never mutated. No production cleanup/reset is performed.

## Small independent oracle

`fixtures/expected.small.json` is written by hand, not evaluated by policy/checker.
`fixtures/actual.synthetic.json` is a separate synthetic positive control, explicitly
marked `dataKind: SYNTHETIC`; it is not a Health export, database seed or authority provider.
All actor/cattle/farm/procedure/batch identifiers are fictional test labels. `doseOrdinal`
is a comparison field, not medical dosage or a definition of clinical dose identity.

Fixture context for a future driver: FA has BA/BB; BS-A assigned FA/BA; FM-A assigned FA;
CA current FA/BA, CB FA/BB, CC FB/BC. Canonical links/grants must come from real owners.

| Intent / attempts | Hand-authored expectation |
|---|---|
| I-01 / A-01, A-02, A-03 | BS-A writes CA; same-content retry resolves ACCEPTED; changed batch attempt CONFLICT. Exactly one original durable effect; content unchanged |
| I-02 / A-04 | FM-A writes CB, ACCEPTED, exactly one effect |
| I-03 / A-05 | BS-A targets CC outside current scope, DENIED, zero effects |
| I-04 / A-06 | New FM acceptance on CA with stale expected custody v6 vs current v7, CONFLICT, zero effects. Current permission may ALLOW; Health must arrange/enforce version guard |
| I-UNEXPECTED / deliberate self-test only | Not in expected intents; any observed durable effect must be flagged UNEXPECTED_EFFECT |

Positive actual: two effect rows, two linked source-evidence rows, seven outcome
observations for six attempts. A-02 has two receipt observations with different
observation IDs, both referencing the SAME effect as A-01. Neither retry attempts nor
repeated receipt responses are counted as new business effects. A-03 conflict does not
require the original I-01 effect to disappear; I-04 independently checks zero effects.
The checker does not derive denial/conflict from role, versions, payload or workflow;
these are expected runtime outcomes, currently unexecuted.

## Verification-only format v1

JSON must be strict (no comments/trailing commas), depth at most 32, duplicate property
names rejected at every nesting level. Mandatory keys and enum strings are case sensitive.
`formatVersion`, `kind`, `dataKind`, `expectedOutcome` and `outcome` must be JSON
string scalars wherever present in the required projection. Arrays (including singleton
arrays), objects, numbers, booleans and null are invalid before enum comparison;
they are never converted to strings to accept an input. Type/value failures return
exit 2 / INVALID / INPUT_INVALID with the specific field path.
Identifiers must be nonblank unpadded strings, compared with ordinal case-sensitive equality.
Arrays remain arrays even empty/singleton. IDs are unique in their respective collections;
intent/attempt/effect/evidence/observation identifiers are separate namespaces.

Both roots require `formatVersion: "clinical-verification/1"` and the same nonblank
`fixtureId`. Required fields below are validated; additional fields are ignored and
cannot override required fields. A changed required projection needs an explicit format
change/owner mapping, not a property-order-dependent hash.
Unknown names such as `Keys`, `Count`, `Values`, `PSBase`, `GetEnumerator`, `get_Keys`,
`ContainsKey` and `Item` are also ignored at root/nested levels. Required-key validation
enumerates real dictionary entries with ordinal key comparison; data cannot substitute
for the key collection. Wrong-case required keys remain missing even when unknown
case-variant fields or a `Keys` value claims they exist.

| Document / row | Required fields |
|---|---|
| Expected root | `kind: "expected"`, nonempty `intents` and `attempts` arrays |
| Expected intent | `intentId`, `actorId`, `cattleId`, `originFarmId`, `payload`, `expectedEffectCount` integer 0 or 1, `requiredEvidenceKinds` array of distinct nonblank strings |
| Expected attempt | Unique `attemptId`, linked expected `intentId`, `expectedOutcome` ACCEPTED/DENIED/CONFLICT, `request` with `actorId`, `cattleId`, `payload` |
| Actual root | `kind: "actual"`, `dataKind: "SYNTHETIC"` or `"RUNTIME_EXPORT"`, `effects`, `sourceEvidence`, `outcomes` arrays |
| Actual effect | Unique `effectId`, `intentId`, `actorId`, `cattleId`, `originFarmId`, `payload` |
| Actual source evidence | Unique `evidenceId`, linked `effectId`, `intentId`, nonblank `kind` |
| Actual outcome observation | Unique `observationId`, `attemptId`, `intentId`, `outcome` ACCEPTED/DENIED/CONFLICT, required `effectId` key (string or null) |
| Payload in every row/request | Nonblank `procedureCode`, `batchId`; positive integer `doseOrdinal` |

One-effect expectations must specify at least one required evidence kind and at least
one ACCEPTED expected attempt. Zero-effect expectations require an empty evidence-kind
list and cannot have ACCEPTED expected attempts. Accepted expected requests must match
the intent's original actor/cattle/payload; a conflict request can deliberately differ.
Each expected intent needs at least one expected attempt. Duplicate expected intent or
attempt keys are invalid, never silently overwritten.

`CLINICAL_ACCEPTED` is this fixture's required source-evidence label, not an assertion
that the owners' production event/enum has that name. The checker supports supplied
required evidence kinds without inventing audit coverage rules. Sensitive read/denial
audit and collector recovery are not represented by these accepted-write evidence rows.

## What is compared

1. **Business cardinality:** exact durable effect count for each expected intent. Unknown
   intents are unexpected effects. Two different effect IDs for a one-effect intent are
   DUPLICATE_CLINICAL_EFFECT. Repeated physical export rows with the same effect ID are
   invalid DUPLICATE_EFFECT_ID; the tool does not deduplicate them or inflate counts.
2. **Immutable content:** compare `actorId`, `cattleId`, `originFarmId`, then explicit
   payload `procedureCode`, `batchId`, `doseOrdinal`. Strings use ordinal equality;
   integer dose ordinals use numeric equality after type validation. JSON property order,
   collection order and unknown/system-generated fields do not determine equality.
   Effect IDs are discovered from actual data, not guessed/hardcoded by the oracle.
3. **Source evidence:** every observed expected effect must have each required kind
   linked to BOTH that effect and its intent. Arbitrary audit rows/wrong kinds cannot
   satisfy this. Evidence referencing a missing effect is orphaned; a different intent
   from that effect is a link mismatch. Duplicate evidence identities are invalid.
4. **Claimed outcomes vs observations:** every expected attempt needs one or more
   outcome observations; each observation must match expected intent/outcome. ACCEPTED
   must reference an observed effect of that same intent. DENIED/CONFLICT must have
   null `effectId` (no accepted effect disclosure in this projection). Unknown attempts
   are unexpected outcomes; duplicate observation IDs are invalid. Multiple distinct
   receipt observations for the same attempt/intent/effect are valid.

Categories: MISSING_EFFECT, DUPLICATE_CLINICAL_EFFECT, UNEXPECTED_EFFECT,
CONTENT_MISMATCH, MISSING_SOURCE_EVIDENCE, ORPHAN_SOURCE_EVIDENCE,
EVIDENCE_LINK_MISMATCH, MISSING_OUTCOME, UNEXPECTED_OUTCOME,
OUTCOME_INTENT_MISMATCH, OUTCOME_MISMATCH, OUTCOME_EFFECT_MISMATCH;
duplicate identities produce specific DUPLICATE_* categories with status INVALID.
Other malformed/missing inputs produce INPUT_INVALID. Checker errors never exit 0.

## Future real-data adapter and limits

Health owner must first provide submit/retry/receipt/history interfaces, deterministic
test setup, canonical custody/record origin, complete durable-effect and source-evidence
exports for an isolated test run. The driver must preserve actual request/attempt IDs
and collect response observations; map native states/IDs/fields to this projection in
a separately reviewed adapter. Do not synthesize effects from receipt responses, suppress
unexpected effects, infer missing source evidence or normalize changed actor/cattle/payload.
Retain raw inputs/exports, run version/config and adapter mapping as evidence. Unknown
additional business fields are outside v1 comparison until owners define them.

Changing dataKind to RUNTIME_EXPORT does not authenticate data or establish completeness.
This checker cannot prove a request was actually sent, all effects were exported, identity
and grants were current, permissions blocked disclosure, origin was assigned canonically,
evidence was atomic/authentic, or an audit collector recovered. It compares supplied data.
No arbitrary dose identity/receipt ACL/revocation/cache/timing policy is added here.
Unknown/multiple tag, UI confirmation, deactivated lookup content, offline recovery,
concurrency and clinical end-to-end behavior need real boundary/domain/UI tests.

T06/T07/T08/T09 runtime remains Not Run pending those owner hooks. T05 offline remains
Design-only/scope pending. Gate T01–T04, collector/load/hardware/T10 are outside Step 3A.
PHUC-03…05 as team obligations are not marked fully complete by this tool's self-tests.
