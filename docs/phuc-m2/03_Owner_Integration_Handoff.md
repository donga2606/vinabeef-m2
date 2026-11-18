# Owner integration handoff

Nguyễn Hữu Phúc · 2352938 · 17/11/2026. **Library/unit và checker R2 đã QA PASS đúng scope; integration thật vẫn Not Run.** IDs trong [evidence index](02_Evidence_and_Contribution_Status.md) dẫn code, commands và raw results. Handoff này để owners nhận implementation có sẵn; không viết adapter/endpoint mới hoặc yêu cầu chọn lại technical baseline.

## Điểm dùng ngay

Dependency `vn.edu.hcmut.vinabeef:access-policy:0.1.0-SNAPSHOT` ở [backend/libs/access-policy](../../backend/libs/access-policy/pom.xml). [README canonical](../../backend/libs/access-policy/README.md) chứa dependency declaration và usage body của `PolicyUsageTest.readOwnOriginAfterTransferExample`, đã compile/assert trong final reactor cũ. Package `vn.edu.hcmut.vinabeef.policy`; các value types/enums nested trong `ClinicalAuthorizationPolicy`. Gọi `evaluate(Action, Subject, ResourceContext, Instant)` với server decision time. Health chưa có dependency/callsite dùng library; đây là phần cần adoption, không wiring đã hoàn tất. [E-POLICY/E-RUNTIME]

| Action | Resource thật phải dựng | Permission subset |
|---|---|---|
| VIEW_CATTLE | CattleResource(CattleContext) | Current BS farm+barn hoặc FM farm; individual lookup |
| SUBMIT_HEALTH | CattleResource(CattleContext) | Current BS farm+barn hoặc FM farm; chỉ permission, chưa clinical acceptance |
| READ_HISTORY | HistoryRecord(CattleContext, ClinicalRecord) | Một record: current scope hoặc FM own-origin; không authorize collection bằng một row |
| READ_RECEIPT | AcceptedReceipt(receiptId, cattle, record) hoặc TargetReceipt(receiptId, cattle) | Accepted record dùng entitlement của record; pending/failed chưa có record chỉ current target scope |

`Decision` có allowed/reason/matchedGrant. ALLOW reasons CURRENT_BARN/CURRENT_FARM/OWN_ORIGIN_RECORD, matched grant reference/version; chọn reference nhỏ nhất theo String.compareTo nếu nhiều grants match. DENY reasons NO_MATCHING_GRANT/ACTION_CONTEXT_MISMATCH, matchedGrant null, không raw subject/clinical data. Public invalid/null/malformed/mismatched-record input throw `IllegalArgumentException`; boundary phải chặn operation và map lỗi không leak existence, không fallback allow. External unknown enum labels reject tại parser. Không dùng metadata version hoặc constructor thành proof authority. [E-POLICY]

## Phú — verified identity/current grants

Map verified human subject thành Subject(subjectId, grants), giữ nguyên từng grant: reference, BS/FM/SA/EM role, farmId/barnIds, ACTIVE/REVOKED/DISABLED, validFrom, optional validUntil, nonnegative version. Interval `[validFrom, validUntil)`, server time; không dùng capturedAt. Một grant phải tự khớp role và toàn scope; không trộn fields giữa grants. Empty BS barns không wildcard. Resolve current version của mỗi reference authoritative; duplicate references trong Subject bị reject.

Model là farm-scoped clinical projection: farmId required cho mọi grant. Global technical grants có thể omit khỏi projection; không bịa farm và không bỏ applicable clinical grants. Xác minh barn/farm membership từ nguồn authority, library chỉ so opaque IDs. SA/EM-only không clinical privilege; independent BS/FM grant có thể allow và được trace bằng matched reference.

Mỗi protected operation phải lấy current applicable grants authoritative, không positive cache/TTL grace. Invalid identity/authority unavailable/freshness unknown → boundary fail closed/unavailable, zero disclosure/effect; không tạo positive input giả. Phú/Khánh còn cần chứng minh implementation và ordering trước acceptance, không chọn lại nguyên tắc đã chốt. [E-DECISIONS/E-POLICY-QA]

## Khánh — canonical context và Health enforcement

- Dựng current cattle/farm/barn/custodyVersion từ owned data; record→cattle và receipt→record phải là actual canonical links. Library chỉ kiểm shape và identifier equality. Record origin là canonical farm tại first acceptance, bất biến qua correction; capture/device/reader farm không thay origin.
- Enforce policy tại mọi protected operation/mode. Filter history records, totals, links/correction links và minimal identity projection; FM own-origin ngoài current farm không mở full destination profile/history hoặc new write. AcceptedReceipt bắt buộc record; missing association không được né bằng TargetReceipt. Pending/failed chưa có record không được tự dựng origin entitlement.
- New acceptance: sau ALLOW còn lifecycle, payload/idempotency, expected custody/resource version, dose/business constraints và effect+receipt+required source-evidence transaction. Current scope đúng nhưng changed payload/stale expected version vẫn là Health conflict/guard, không đổi thành permission reason. Source evidence failure không accepted hoặc partial effect.
- Existing committed intent: resolve bằng current READ_RECEIPT entitlement, không chạy lại stale new-acceptance expectedVersion guard. Original actor/knowing ID không cấp thêm quyền. Không delegated submit/adopt/reassign; current farm ALLOW không đủ để workflow submit thay người khác.
- Revalidate/serialize current grants/custody với final acceptance và ghi operation ordering; hai snapshot reads hoặc versions riêng không chứng minh distributed race safety. Protected reads cũng cần current permission. Denial logging failure không đổi DENY thành ALLOW; sensitive-read/denial evidence và collector recovery cần coverage riêng.
- Known deactivated cattle giữ authorized identity/history; new-write lifecycle riêng. Unknown unresolved/no auto-create và UI selection/confirmation phải được xử lý ở lookup/application boundary. [E-POLICY/E-RUNTIME]

## Hooks cho verification thật

Khánh cung cấp native submit/retry/receipt/history interfaces và controlled fixture setup trước khi Phúc viết real driver. Không giả endpoint paths hoặc public DTOs trong lượt docs này. Driver cần ghi actual request/attempt IDs, actor/cattle/payload đã gửi, native response/error và receipt observations; receipt lặp không tạo durable effect mới.

Export **đầy đủ trong isolated test run**: durable effects với actual effectId/intentId/actorId/cattleId/immutable origin/payload; accepted-write source evidence với evidenceId/effectId/intentId/kind. Giữ raw exports và completeness/run-isolation explanation, không synthesize effect từ receipt hoặc lọc mất unexpected effect. Phú/Khánh xác nhận native evidence kinds và source-failure hook; grant revoke/expiry/unavailable và custody/version-change hooks phải quan sát được.

Map native data qua adapter được review sang [verification-only format](../../verification/clinical/README.md). Không coi format/CLINICAL_ACCEPTED label hoặc doseOrdinal là Health public contract/dose identity. `dataKind: RUNTIME_EXPORT` không chứng minh authenticity/completeness. Checker exits 0 match, 1 mismatch, 2 invalid, 3 checker error; raw result/exit phải giữ. Presence đúng source row không chứng minh atomicity, audit collector completeness hoặc UI behavior. [E-CHECKER-R2/E-QA-R2]

## Thông — UI và offline boundary

Supply app build/flow/hooks cho unknown/multiple tags, known-deactivated authorized/denied history, selected-cattle confirmation và form cattle change. Không auto-create/vaccinate khi scan; không silently carry payload của cattle A sang B; pending/unknown/rejected/review khác accepted. Chỉ current-authorized server receipt xác nhận accepted. Snapshot rows không chứng minh các thao tác UI này.

Nếu nhóm nhận offline code: cần actual durable draft/restart/sync và protected original-actor recovery hooks. A/B và budgets 30 phút/20 intents/120 giây vẫn proposal trong design, không hardcode bằng handoff này hoặc lấy dashboard lease cấp mobile permission. T05 chưa runtime acceptance. [E-DESIGN/E-RUNTIME]

## Checklist chuyển từ Not Run sang runnable

Không yêu cầu họp/biên bản mới: nhận trực tiếp artifact/code path/export/hook và reviewer xác nhận mapping là đủ để tiến tới run. Các dòng sau **chưa được xác nhận fulfilled** trong snapshot hiện tại.

| Cần nhận | Owner | Artifact/criterion review |
|---|---|---|
| Verified current-grant adapter và failure behavior | Phú + Khánh | Real producer/callsite path; exact Subject/Grant mapping; per-operation authority/no-cache và unavailable denial có cách quan sát |
| Protected operation enforcement | Khánh | Actual API/application callsites cho four actions/modes, current receipt routing và read/no-disclosure assertions; library dependency được dùng thật |
| Canonical links/filtering/transactions | Khánh | Actual cattle/record/receipt resolution, per-record filtering/totals/links, origin/correction rule và acceptance/evidence ordering được review |
| Controlled domain/authority faults | Khánh + Phú | Setup/hooks cho same-intent changed payload, stale new version, committed resolution, revoke/unavailable, custody/grant change và source-evidence failure; no effect/disclosure checks tương ứng |
| Complete raw exports + response capture | Khánh + Phúc | Isolated run, actual request/attempt/response records, all durable effects/evidence; native→verification format reviewed, completeness không suy từ receipt |
| T09 observable UI cases | Thông + Khánh | Runnable build và observable lookup/selection/confirmation/state/history cases; không thay bằng checker rows |
| Conditional T05 | Thông + Phú + Khánh | Nếu nhận offline runtime, supply durable-store/restart/eligibility/sync hooks và approved scope/measures; nếu chưa thì giữ Design-only/scope pending |
| Run bundle + recorded result | Phúc phối hợp owners | Khi artifacts liên quan đủ: ghi version/dirty-source hashes, non-secret config/setup, exact command, raw actual, independent expected, diagnostics và limitations; chỉ sau run thật mới cập nhật Passed/Failed |

Core server cases có thể runnable khi dependencies liên quan đủ; T09 UI/T05 offline có gates riêng. Đủ checklist không tự là test PASS. Phúc review/adapt [report draft](01_Phuc_Report_Section_EN.md) và AI/reflection declaration trước nhóm merge; Thông/Phú nhận section/evidence để review, không được coi đã gửi hoặc accepted ở lượt này. [E-PLAN/E-CHANGE]
