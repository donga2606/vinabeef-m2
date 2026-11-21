package vn.edu.hcmut.vinabeef.health.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.vinabeef.health.application.port.out.HealthRepository;
import vn.edu.hcmut.vinabeef.health.application.port.in.GetHealthHistoryUseCase;
import vn.edu.hcmut.vinabeef.health.application.port.in.GetHealthIntentReceiptUseCase;
import vn.edu.hcmut.vinabeef.health.application.port.in.LookupCattleUseCase;
import vn.edu.hcmut.vinabeef.health.application.port.in.SubmitHealthIntentUseCase;
import vn.edu.hcmut.vinabeef.health.application.port.in.SubmitHealthIntentUseCase.SubmissionResult;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.AcceptedReceiptContext;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResource;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResourceContext;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ClinicalRecord;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ExistingIntent;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HistoryPage;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthIntentSubmission;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthReceipt;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.IntentStatus;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyAction;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.RecordType;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.RegistrationStatus;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ResourceContext;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ScheduleState;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.TargetReceiptContext;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HistoryRecordContext;

@Service
public class HealthIntentService implements LookupCattleUseCase, SubmitHealthIntentUseCase,
        GetHealthIntentReceiptUseCase, GetHealthHistoryUseCase {
    private final HealthRepository repository;
    private final HealthAuthorization authorization;
    private final Clock clock;

    public HealthIntentService(HealthRepository repository, HealthAuthorization authorization, Clock clock) {
        this.repository = repository;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @Override
    public CattleResource lookupByRfid(String rfid) {
        if (rfid == null || rfid.isBlank()) throw HealthException.invalid("rfid is required.");
        HealthAuthorization.Session session = authorization.openSession();
        CattleResource cattle = repository.findCattleByRfid(rfid.strip())
                .orElseThrow(() -> HealthException.notFound("cattle"));
        if (!authorization.allowed(session, PolicyAction.VIEW_CATTLE,
                new CattleResourceContext(cattle.policyContext()))) {
            throw HealthException.notFound("cattle");
        }
        return cattle;
    }

    @Transactional
    @Override
    public SubmissionResult submit(HealthIntentSubmission submission) {
        validateSubmission(submission);
        String payloadHash = payloadHash(submission);

        Optional<ExistingIntent> earlier = repository.findIntent(submission.intentId());
        if (earlier.isPresent()) return replayOrConflict(earlier.get(), payloadHash);

        HealthAuthorization.Session beforeClaim = authorization.openSession();
        CattleResource target = resolveTarget(submission);
        authorization.requireAllowed(beforeClaim, PolicyAction.SUBMIT_HEALTH,
                new CattleResourceContext(target.policyContext()));
        Optional<ClinicalRecord> correctionTarget = correctionTarget(submission, target);
        validateScheduleReference(submission, target, correctionTarget.orElse(null));

        Instant now = clock.instant();
        if (!repository.tryClaimIntent(submission, payloadHash, beforeClaim.principal().subjectId(), target, now)) {
            ExistingIntent winner = repository.findIntent(submission.intentId())
                    .orElseThrow(() -> HealthException.unavailable("IDEMPOTENCY_LOOKUP_FAILED"));
            return replayOrConflict(winner, payloadHash);
        }

        CattleResource locked = repository.lockCattle(target.cattleId())
                .orElseThrow(() -> HealthException.notFound("cattle"));
        HealthAuthorization.Session finalSession = authorization.openSession();
        if (!finalSession.principal().subjectId().equals(beforeClaim.principal().subjectId())) {
            throw HealthException.unauthenticated();
        }
        // Recheck current grants after the cattle row is locked, immediately before acceptance.
        authorization.requireAllowed(finalSession, PolicyAction.SUBMIT_HEALTH,
                new CattleResourceContext(locked.policyContext()));

        if (locked.registrationStatus() == RegistrationStatus.INACTIVE) {
            return persistConflict(submission, payloadHash, finalSession.principal().subjectId(), locked,
                    IntentStatus.REJECTED, "CATTLE_INACTIVE", now);
        }
        boolean custodyChangedDuringSubmit = !target.farmId().equals(locked.farmId())
                || !target.barnId().equals(locked.barnId())
                || target.custodyVersion() != locked.custodyVersion();
        if (locked.resourceVersion() != submission.expectedVersion() || custodyChangedDuringSubmit) {
            return persistConflict(submission, payloadHash, finalSession.principal().subjectId(), locked,
                    IntentStatus.NEEDS_REVIEW,
                    custodyChangedDuringSubmit ? "CUSTODY_CHANGED" : "RESOURCE_VERSION_CHANGED", now);
        }

        ClinicalRecord correction = correctionTarget.orElse(null);
        if (correction != null) {
            Optional<ClinicalRecord> latest = repository.latestRecord(locked.cattleId());
            if (latest.isEmpty() || !latest.get().recordId().equals(correction.recordId())) {
                return persistConflict(submission, payloadHash, finalSession.principal().subjectId(), locked,
                        IntentStatus.NEEDS_REVIEW, "CORRECTION_TARGET_CHANGED", now);
            }
        }

        ScheduleState schedule = null;
        UUID requestedScheduleId = submission.scheduleId();
        UUID effectiveScheduleId = correction == null ? requestedScheduleId : correction.scheduleId();
        if (correction != null && requestedScheduleId != null
                && !requestedScheduleId.equals(correction.scheduleId())) {
            throw HealthException.invalid("A correction must retain its original scheduled-dose link.");
        }
        if (effectiveScheduleId != null) {
            schedule = repository.lockSchedule(effectiveScheduleId)
                    .orElseThrow(() -> HealthException.invalid("scheduleId is not a known vaccination schedule."));
            if (!schedule.cattleId().equals(locked.cattleId())) {
                throw HealthException.invalid("scheduleId does not belong to the selected cattle.");
            }
            if (!"SCHEDULED".equals(schedule.status())) {
                return persistConflict(submission, payloadHash, finalSession.principal().subjectId(), locked,
                        IntentStatus.REJECTED, "SCHEDULE_NOT_ACTIVE", now);
            }
            if (correction == null && schedule.effectiveRecordId() != null) {
                return persistConflict(submission, payloadHash, finalSession.principal().subjectId(), locked,
                        IntentStatus.REJECTED, "SCHEDULE_ALREADY_COMPLETED", now);
            }
            if (correction != null && !correction.recordId().equals(schedule.effectiveRecordId())) {
                return persistConflict(submission, payloadHash, finalSession.principal().subjectId(), locked,
                        IntentStatus.NEEDS_REVIEW, "CORRECTION_TARGET_CHANGED", now);
            }
        }

        long nextVersion = locked.resourceVersion() + 1;
        UUID recordId = UUID.randomUUID();
        String originFarmId = correction == null ? locked.farmId() : correction.originFarmId();
        ClinicalRecord record = new ClinicalRecord(recordId, locked.cattleId(), nextVersion,
                submission.type(), submission.canonicalPayloadJson(), submission.occurredAt(), now,
                submission.deviceRef(), originFarmId, effectiveScheduleId,
                correction == null ? null : correction.recordId(),
                correction == null ? null : submission.correctionReason());
        HealthReceipt receipt = new HealthReceipt(submission.intentId(), locked.cattleId(),
                IntentStatus.ACCEPTED, recordId, nextVersion, null, false, now);
        repository.completeIntent(submission, payloadHash, finalSession.principal().subjectId(),
                locked, receipt, 201, record, now);
        return new SubmissionResult(201, receipt);
    }

    @Transactional(readOnly = true)
    @Override
    public HealthReceipt receipt(UUID intentId) {
        HealthAuthorization.Session session = authorization.openSession();
        ExistingIntent intent = repository.findIntent(intentId)
                .orElseThrow(() -> HealthException.notFound("health intent"));
        authorizeReceipt(intent, session, false);
        return intent.receipt();
    }

    @Transactional(readOnly = true)
    @Override
    public HistoryPage history(UUID cattleId, Long beforeVersion, int limit) {
        if (limit < 1 || limit > 100) throw HealthException.invalid("limit must be between 1 and 100.");
        long cursor = beforeVersion == null ? Long.MAX_VALUE : beforeVersion;
        if (cursor < 1) throw HealthException.invalid("beforeVersion must be positive.");
        HealthAuthorization.Session session = authorization.openSession();
        Optional<CattleResource> target = repository.findCattleById(cattleId);
        if (target.isEmpty()) return new HistoryPage(cattleId, List.of(), null);

        List<ClinicalRecord> scanned = repository.history(cattleId, cursor, limit + 1);
        boolean hasMore = scanned.size() > limit;
        List<ClinicalRecord> visible = new ArrayList<>();
        int count = Math.min(limit, scanned.size());
        for (int i = 0; i < count; i++) {
            ClinicalRecord record = scanned.get(i);
            ResourceContext resource = new HistoryRecordContext(target.get().policyContext(), record.policyRef());
            if (authorization.allowed(session, PolicyAction.READ_HISTORY, resource)) visible.add(record);
        }
        Long next = hasMore && count > 0 ? scanned.get(count - 1).recordVersion() : null;
        return new HistoryPage(cattleId, visible, next);
    }

    private SubmissionResult replayOrConflict(ExistingIntent existing, String requestedHash) {
        HealthAuthorization.Session session = authorization.openSession();
        authorizeReceipt(existing, session, true);
        if (!existing.payloadHash().equals(requestedHash)) {
            throw HealthException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "This intent ID is already bound to another payload.");
        }
        return new SubmissionResult(200, existing.receipt().asReplay());
    }

    private void authorizeReceipt(ExistingIntent intent, HealthAuthorization.Session session, boolean hideAsForbidden) {
        CattleResource current = repository.findCattleById(intent.cattleId())
                .orElseThrow(() -> hideAsForbidden ? HealthException.forbidden() : HealthException.notFound("health intent"));
        ResourceContext resource;
        if (intent.status() == IntentStatus.ACCEPTED) {
            if (intent.record() == null) throw HealthException.unavailable("ACCEPTED_RECORD_MISSING");
            resource = new AcceptedReceiptContext(intent.receipt().intentId().toString(),
                    current.policyContext(), intent.record().policyRef());
        } else {
            if (intent.record() != null) throw HealthException.unavailable("RECEIPT_RECORD_LINK_INVALID");
            resource = new TargetReceiptContext(intent.receipt().intentId().toString(), current.policyContext());
        }
        if (!authorization.allowed(session, PolicyAction.READ_RECEIPT, resource)) {
            if (hideAsForbidden) throw HealthException.forbidden();
            throw HealthException.notFound("health intent");
        }
    }

    private SubmissionResult persistConflict(HealthIntentSubmission submission, String payloadHash, String actorSub,
                                        CattleResource cattle, IntentStatus status, String reasonCode, Instant now) {
        HealthReceipt receipt = new HealthReceipt(submission.intentId(), cattle.cattleId(), status,
                null, cattle.resourceVersion(), reasonCode, false, now);
        repository.completeIntent(submission, payloadHash, actorSub, cattle, receipt, 409, null, now);
        return new SubmissionResult(409, receipt);
    }

    private CattleResource resolveTarget(HealthIntentSubmission submission) {
        if ((submission.cattleId() == null) == (submission.rfid() == null)) {
            throw HealthException.invalid("Supply exactly one of cattleId or rfid.");
        }
        return submission.cattleId() != null
                ? repository.findCattleById(submission.cattleId()).orElseThrow(HealthException::forbidden)
                : repository.findCattleByRfid(submission.rfid()).orElseThrow(HealthException::forbidden);
    }

    private Optional<ClinicalRecord> correctionTarget(HealthIntentSubmission submission, CattleResource cattle) {
        if (submission.correctionOfRecordId() == null) return Optional.empty();
        ClinicalRecord record = repository.findRecord(submission.correctionOfRecordId())
                .orElseThrow(() -> HealthException.invalid("correctionOfRecordId must belong to the selected cattle."));
        if (!record.cattleId().equals(cattle.cattleId())) {
            throw HealthException.invalid("correctionOfRecordId must belong to the selected cattle.");
        }
        if (record.type() != submission.type()) {
            throw HealthException.invalid("A correction must retain the original clinical record type.");
        }
        return Optional.of(record);
    }

    private void validateScheduleReference(HealthIntentSubmission submission, CattleResource cattle,
                                          ClinicalRecord correction) {
        if (submission.scheduleId() != null && submission.type() != RecordType.VACCINATION) {
            throw HealthException.invalid("scheduleId is only valid for a vaccination intent.");
        }
        if (correction == null && submission.scheduleId() == null) return;
        UUID scheduleId = correction == null ? submission.scheduleId() : correction.scheduleId();
        if (correction != null && submission.scheduleId() != null
                && !submission.scheduleId().equals(correction.scheduleId())) {
            throw HealthException.invalid("A correction must retain its original scheduled-dose link.");
        }
        if (scheduleId != null) {
            if (submission.type() != RecordType.VACCINATION) {
                throw HealthException.invalid("A scheduled-dose correction must remain a vaccination record.");
            }
            ScheduleState schedule = repository.findSchedule(scheduleId)
                    .orElseThrow(() -> HealthException.invalid("scheduleId must refer to a vaccination schedule for the selected cattle."));
            if (!schedule.cattleId().equals(cattle.cattleId())) {
                throw HealthException.invalid("scheduleId must refer to a vaccination schedule for the selected cattle.");
            }
        }
    }

    private void validateSubmission(HealthIntentSubmission submission) {
        if (submission.type() == null || submission.canonicalPayloadJson() == null
                || !submission.canonicalPayloadJson().stripLeading().startsWith("{")) {
            throw HealthException.invalid("payload must be a JSON object and type is required.");
        }
    }

    private String payloadHash(HealthIntentSubmission submission) {
        List<String> values = List.of(
                submission.intentId().toString(),
                submission.cattleId() == null ? "" : submission.cattleId().toString(),
                submission.rfid() == null ? "" : submission.rfid(),
                submission.type().name(), submission.canonicalPayloadJson(), submission.occurredAt().toString(),
                submission.deviceRef(), Long.toString(submission.expectedVersion()),
                submission.scheduleId() == null ? "" : submission.scheduleId().toString(),
                submission.correctionOfRecordId() == null ? "" : submission.correctionOfRecordId().toString(),
                submission.correctionReason() == null ? "" : submission.correctionReason());
        StringBuilder material = new StringBuilder();
        for (String value : values) material.append(value.length()).append(':').append(value);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

}
