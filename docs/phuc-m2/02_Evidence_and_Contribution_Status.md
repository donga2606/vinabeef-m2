# Evidence và contribution status — Step 4A

Nguyễn Hữu Phúc · 2352938 · 17/11/2026, Asia/Saigon. IDs dưới đây dùng cho [section report EN](01_Phuc_Report_Section_EN.md) và [owner handoff](03_Owner_Integration_Handoff.md). Bảng này ghi nguồn và kết quả kiểm chứng theo từng phạm vi. Phúc giữ đề bài, hồ sơ thiết kế, log gốc và biên bản QA để nhóm đối chiếu khi cần. Các liên kết trong bản bàn giao này mở tài liệu và mã nguồn có trên nhánh.

## E-SOURCE

Source register cùng JSON register xác định S1/S2 snapshots ngày 15/11 và nguồn M1/M2/material. M2 PDF tr.1–3: context, quality issues/requirements, architecture/SOLID/reflection và Task 2 ít nhất một main module. M1 đã nộp: tr.9 deactivated identity/history, tr.12–18 NFR/RBAC/audit, tr.20–29 SC/QAS, tr.35–48 existing edge/ownership, tr.50–55 ADRs. Các page refs là trang PDF đã tra từ extracts, không số slide. Không nhận đã đọc live revision Drive mới.

## E-DESIGN

Quality Design R2, Policy Matrix R2, R0 revision/findings, English draft cũ, AI usage design và QA design R2 PASS. QA là coordinating AI document review, không owner/R0/H0 approval. Detailed six-field QAS09/10 ở Quality §§6–7; budgets P/A-B vẫn chờ quyết định nhóm. Design text “Not Run” là lịch sử của gói thiết kế; status implementation hiện tại lấy các evidence IDs dưới đây, không sửa nguồn cũ.

## E-DECISIONS

Technical baseline 17/11: người dùng đã ủy quyền chọn phần Phúc; whole-grant additive RBAC, per-record origin/receipt, current authoritative grants mỗi operation/no positive cache và không delegated submit là lựa chọn áp dụng cho code độc lập. Không ghi các lựa chọn đó “chưa chọn” nữa. Producer/consumer review, real adapters/enforcement/ordering và R0/H0 team evidence vẫn chưa được nhận đã có.

## E-POLICY

Canonical [ClinicalAuthorizationPolicy.java](../../backend/libs/access-policy/src/main/java/vn/edu/hcmut/vinabeef/policy/ClinicalAuthorizationPolicy.java), [POM](../../backend/libs/access-policy/pom.xml), [README API/usage](../../backend/libs/access-policy/README.md). Dependency coordinates `vn.edu.hcmut.vinabeef:access-policy:0.1.0-SNAPSHOT`; Java 21 plain JAR. Actual `evaluate(Action, Subject, ResourceContext, Instant)`, four typed resource variants và immutable nested records. [ClinicalAuthorizationPolicyTest](../../backend/libs/access-policy/src/test/java/vn/edu/hcmut/vinabeef/policy/ClinicalAuthorizationPolicyTest.java) có 41 invocations; [PolicyModelTest](../../backend/libs/access-policy/src/test/java/vn/edu/hcmut/vinabeef/policy/PolicyModelTest.java) 34; [PolicyUsageTest](../../backend/libs/access-policy/src/test/java/vn/edu/hcmut/vinabeef/policy/PolicyUsageTest.java) 1. Counts lấy XML, không annotations.

`readOwnOriginAfterTransferExample` là body usage đã compile/assert trong run cũ, cùng snippet README. 33-row mapping phân biệt pure projections và deferred B/H/U; integration/SOLID notes phân định authority/Health obligations. Dependency tree, effective POM, JAR inventory/hash: chỉ JUnit test dependencies, production imports java.*, không Spring runtime/repackage. Không nhận constructor/version là proof identity/canonical/freshness.

## E-BASELINE

Recorded command tại repo root: `pwsh -NoLogo -NoProfile -File scripts/dev.ps1 mvn -B verify`. Raw log, exit/time metadata, 5 XML suites, baseline manifest, QA Step 2B. Exit 0/BUILD SUCCESS; 27 invocations, zero failures/errors/skips. Maven 3.9.11 / Java 21.0.9 Eclipse Adoptium, Docker Linux amd64. Health có zero tests. Đây là baseline trước thêm policy, không unique coverage cộng thêm vào final reactor.

## E-REACTOR

Cùng command full verify ở repo root, final run 17/11 16:07:36–16:10:13 +07: raw log, exit metadata, 8 actual copied XML suites, toolchain, implementation manifest. Exit 0/BUILD SUCCESS; 103 invocations = 76 policy + 27 owner, zero failures/errors/skips. Same Maven/Java runtime as baseline.

Suite totals: policy 41/34/1; owner MovementEventContractTest 2, PassageClassifierTest 8, MovementUnitTest 3, ObservationGeneratorTest 8, TagObservationContractTest 6. Full run supersedes the earlier display-label correction run. Neither suite elapsed time nor build duration is gate deadline/performance evidence. Dependency-diagnostic quoting error and earlier run remain in original handoff history, not hidden or counted as clinical failures.

## E-POLICY-QA

QA Step 2C: **PASS library/unit scope** after independent coordinating AI source/evidence review, including 103-count XML/hash consistency. No Health adoption, current-grant provider, protected-query enforcement or human sign-off claimed. Historical implementation handoff predates this verdict; its old “QA pending” line does not describe current library QA status.

## E-CHECKER-R2

Canonical [checker](../../verification/clinical/Check-ClinicalSnapshot.ps1), [self-test](../../verification/clinical/Test-ClinicalChecker.ps1), [README format/commands](../../verification/clinical/README.md), [expected](../../verification/clinical/fixtures/expected.small.json), [synthetic actual](../../verification/clinical/fixtures/actual.synthetic.json). Format `clinical-verification/1` is verification-only, not a public Health contract. Expected is hand-authored; actual is explicitly SYNTHETIC. Positive has 4 intents / 6 attempts / 2 durable effects / 2 evidence rows / 7 observations, including repeated receipt. Effect IDs are discovered from actual input. No permission/version/clinical rule is inferred by the checker.

## E-CHECKER-RUN

Recorded command at repo root: `pwsh -NoLogo -NoProfile -File verification/clinical/Test-ClinicalChecker.ps1`. Host/child PowerShell 7.6.9; R2 raw self-test, exit/time metadata, R2 manifest. Exit 0; 82 controls PASS, zero failed; groups baseline 34 + QA-3A-01 42 + QA-3A-02 6. Child checker exits 5 positive/0, 20 mismatch/1, 57 invalid/2 as expected. All variant inputs and canonical inputs preserved. Standalone positive JSON and metadata separately show exit 0. These are synthetic tool results, not app acceptance.

## E-QA-R2

QA Step 3A R2: **PASS checker/synthetic scope**, QA-3A-01/02 **CLOSED** by the independent coordinating AI reviewer. 82-control independent rerun, exit metadata, 10 independent probes, probe script, hash verification. 82/82 and separate 10/10 pass; input/hash preservation confirmed. These were reviewer executions, not new Step 4 runs or human review. R2 checker SHA256 is `e8caee4bb40923ed8de5e539e37eb246c00ddbc9fee8556de9d269d4042068b8`.

## E-R1-DEFECTS

QA Step 3A R1 NEEDS REVISION, original independent results, R2 findings resolution, before replay, after replay. Original 34 controls passed but probes exposed five false passes from array/string coercion and one false rejection from Keys shadowing. Fixed by explicit scalar validation/noncoercing comparison and real dictionary-entry enumeration; reviewer closed both in E-QA-R2. These are tool-contract defects, not production clinical bugs. R1 artifacts stay historical.

## E-RUNTIME

[Actual repo README](../../README.md) and [Health POM](../../backend/apps/herd-health/pom.xml) at the recorded local snapshot: Health skeleton/no business endpoints; no access-policy dependency/callsite adopted there. Runtime mapping remains the detailed dependency list: T06/T07/T08/T09 Not Run/Blocked by absent API/setup/complete export/hooks; T05 Design-only/scope pending. Gate owner code/tests exist but do not establish T01–T04 deadline/load/reader hardware or collector recovery acceptance. No clinical run was performed to prepare this report.

## E-PLAN

Individual plan, S1 team snapshot, S2 member snapshot. Requirements/design scope remains system-wide; policy/checker support the mobile/Health direction. Reducing a full verification platform is a proposal requiring team acknowledgement, not silent completion/removal of PHUC-03…05 obligations.

## E-CHANGE

Revision 2 sửa QA-4A-01 của QA report R1: report EN nay dùng `[validFrom, validUntil)` và giải thích inclusive start/exclusive end, no-fixed-expiry khi validUntil absent nhưng vẫn current ACTIVE authority; citation group đã cân đối. R1 giữ nguyên lịch sử NEEDS REVISION. Bản report R2 đã qua QA của session điều phối và đang chờ Phúc/nhóm duyệt để đưa vào report chung. Policy/unit và checker R2 giữ kết quả QA theo phạm vi đã kiểm.

Hồ sơ Step 4 lưu kết quả đối chiếu tài liệu với mã nguồn và bằng chứng kiểm thử. QA cuối của bản R2 xác nhận số liệu, liên kết và khoảng hiệu lực của grant. Các mục dưới đây ghi commit và mã băm để nhóm đối chiếu bản mã nguồn đã kiểm.

## Reviewed implementation snapshot

Các mã băm SHA-256 dưới đây được tính từ nội dung Git của [commit triển khai `3722f37`](https://github.com/donga2606/vinabeef-m2/commit/3722f3742b8080ecd78fc23f7ece8da76282c303). Các liên kết tới mã nguồn mở file tương ứng trong nhánh đang đọc.

| Code/config/fixture/document path | SHA-256 in implementation commit |
|---|---|
| [backend/libs/access-policy/README.md](../../backend/libs/access-policy/README.md) | `fbf545114e1edc27c4919a544ea7050331fd2617e397cfce6f11d30d321c050a` |
| [backend/libs/access-policy/pom.xml](../../backend/libs/access-policy/pom.xml) | `659c499f07c34005416e2aef1300a9e0fecef3856a75f089059ebf85e6c2e4b5` |
| [backend/libs/access-policy/src/main/java/vn/edu/hcmut/vinabeef/policy/ClinicalAuthorizationPolicy.java](../../backend/libs/access-policy/src/main/java/vn/edu/hcmut/vinabeef/policy/ClinicalAuthorizationPolicy.java) | `b39acf7aca2abebbe0179d954adab71736c5546490dfd9cc30a7c3a685f43fcf` |
| [backend/libs/access-policy/src/test/java/vn/edu/hcmut/vinabeef/policy/ClinicalAuthorizationPolicyTest.java](../../backend/libs/access-policy/src/test/java/vn/edu/hcmut/vinabeef/policy/ClinicalAuthorizationPolicyTest.java) | `1640b842586c289839227c162181ed6f48ae87ee01accd13d7d022649b29cc0d` |
| [backend/libs/access-policy/src/test/java/vn/edu/hcmut/vinabeef/policy/PolicyModelTest.java](../../backend/libs/access-policy/src/test/java/vn/edu/hcmut/vinabeef/policy/PolicyModelTest.java) | `de3cf7ba64da03eaabf45c2353bfac0d5a5df34eb203a724c1f21f221d5224ff` |
| [backend/libs/access-policy/src/test/java/vn/edu/hcmut/vinabeef/policy/PolicyUsageTest.java](../../backend/libs/access-policy/src/test/java/vn/edu/hcmut/vinabeef/policy/PolicyUsageTest.java) | `18b128fc5c68496d974dd90a5fec5720d8bc80dcd38f0a9f2a0e3bb62a26fbd3` |
| [backend/pom.xml](../../backend/pom.xml) | `a2e7ad3eae819ada21f20f22d09fbfc70f4354722eacb4e08c0c5d61c375ee22` |
| [verification/clinical/Check-ClinicalSnapshot.ps1](../../verification/clinical/Check-ClinicalSnapshot.ps1) | `e8caee4bb40923ed8de5e539e37eb246c00ddbc9fee8556de9d269d4042068b8` |
| [verification/clinical/README.md](../../verification/clinical/README.md) | `fd0cbd6f867ec94c6484338fcfb0fbc5debe70c584b3c47ad47f7147d6c42fc6` |
| [verification/clinical/Test-ClinicalChecker.ps1](../../verification/clinical/Test-ClinicalChecker.ps1) | `8f0f330883b2baba254b8732da67bc31f6c811b4273101bd48673dddfaf38982` |
| [verification/clinical/fixtures/actual.synthetic.json](../../verification/clinical/fixtures/actual.synthetic.json) | `abbd9c3756a0ab661af650f238f1e9675211505f09e05cf9c16d7fb602eea9af` |
| [verification/clinical/fixtures/expected.small.json](../../verification/clinical/fixtures/expected.small.json) | `c3365a39ad3a6fb2cf289efa0333ce9c9a4e9a1eda5325317a631ba0fbd2f4bd` |

## Contribution status

| Task | Done local artifact | Reviewer QA | Owner/human acceptance still required |
|---|---|---|---|
| PHUC-R0 | A1 packet and R2 revision/findings prepared; preliminary A3/A4/A7 review | Design document QA PASS | Phúc validates/adopts; related owner packets/responses and actual R0/H0 evidence, no delivery/sign-off assumed |
| PHUC-01 | System-wide quality delta, full QAS09/10 and policy design matrix | Design R2 PASS for review draft | Khánh/Thông review A1, shared views/ADR/scope/budgets adoption |
| PHUC-02 | Pure library/unit implementation and integration/SOLID notes complete locally | Step 2C PASS library/unit | Phú authority adapter + Khánh endpoint adoption/filtering/ordering; clinical enforcement proof absent |
| PHUC-03…05 | Small fixtures/read-only checker prepared, input-contract defects corrected | Step 3A R2 PASS tool/synthetic scope; findings closed | T06/T07/T08/T09 runtime Not Run; actual owner API/setup/exports/hooks and any agreed task-scope change still needed |
| PHUC-06 | This EN section, evidence index and owner handoff prepared as drafts | Step 4A R2 passed coordinating AI document QA | Phúc edits reflection/AI declaration; team merges/reviews final report, verifies final evidence and submission |

## Requirements/scenarios trace without duplicating all 33 rows

| Design trace | Local implementation/tool projection | Actual evidence / deferred runtime |
|---|---|---|
| NFR-04, SC-04; QAS04/10; R2 current scope/origin/receipt cases | ClinicalAuthorizationPolicy whole-grant/time/typed-context rules; matrix projection mapping | E-POLICY/E-REACTOR/E-POLICY-QA. T07 authority/enforcement/query/no-disclosure/ordering Not Run |
| NFR-02, SC-02; QAS05/09; M20/M21a/M26a/c | Library permission separated from Health conflict/version; checker one effect per intent, content and outcome links | E-CHECKER-R2/E-CHECKER-RUN/E-QA-R2 tool only. T06 payload/idempotency/version/concurrency transactions Not Run; T05 Design-only/scope pending |
| NFR-05, SC-05; QAS05 | Checker required source evidence linked to effect AND intent, orphan/wrong-kind detection | Synthetic controls pass; source presence cannot prove atomicity, authenticity, access/denial coverage or canonical collector completeness. T08 Not Run |
| Proposed P-SC-U, QAS10-ID; U1a/U1b+/U1b-/U2–U6, M29 | Library known-resource read-scope projection; checker wrong-cattle/content detection | Pure/tool evidence only; real unknown/deactivated/history content, selection/confirmation/form/pending-state UI T09 Not Run |
| NFR-01/03, SC-01/03; QAS01–03 | Design retains local durable completion/recovery boundary; owner gate code exists | Baseline/final owner tests do not prove each read ≤2 s, burst/WAN/reader LAN capacity, no-loss/collector recovery or hardware qualification |
| NFR-06/07/08, SC-06/07/08; QAS06–08 | Pure library supports separation; no new adapter/load framework | External API qualification, current-scale/provisioning and two compatible adapter substitutions remain unverified; no architecture requirement silently removed |

Counts and verdicts above are attributed to their recorded scopes; they are not summed into a global test total or a final group acceptance claim.
