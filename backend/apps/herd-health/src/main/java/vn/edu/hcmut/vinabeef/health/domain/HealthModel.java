package vn.edu.hcmut.vinabeef.health.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class HealthModel {
    private HealthModel() {}

    public enum RecordType { VACCINATION, HEALTH_STATUS, TREATMENT }
    public enum IntentStatus { ACCEPTED, NEEDS_REVIEW, REJECTED }
    public enum RegistrationStatus { ACTIVE, INACTIVE }
    public enum PolicyAction { VIEW_CATTLE, SUBMIT_HEALTH, READ_HISTORY, READ_RECEIPT }
    public enum PolicyRole { BS, FM, SA, EM }
    public enum GrantStatus { ACTIVE, REVOKED, DISABLED }

    public record CattleResource(
            UUID cattleId,
            String rfid,
            String displayName,
            RegistrationStatus registrationStatus,
            String farmId,
            String farmName,
            String barnId,
            long resourceVersion,
            long custodyVersion) {
        public CattleResource {
            if (cattleId == null || rfid == null || displayName == null || registrationStatus == null
                    || farmId == null || farmName == null || barnId == null
                    || resourceVersion < 0 || custodyVersion < 0) {
                throw new IllegalArgumentException("Invalid canonical cattle resource");
            }
        }

        public CattleContext policyContext() {
            return new CattleContext(cattleId.toString(), farmId, barnId, custodyVersion);
        }
    }

    public record CattleContext(String cattleId, String farmId, String barnId, long custodyVersion) {
        public CattleContext {
            requireIdentifier(cattleId, "cattleId");
            requireIdentifier(farmId, "farmId");
            requireIdentifier(barnId, "barnId");
            if (custodyVersion < 0) throw new IllegalArgumentException("custodyVersion must be nonnegative");
        }
    }

    public sealed interface ResourceContext permits CattleResourceContext, HistoryRecordContext,
            AcceptedReceiptContext, TargetReceiptContext {
        CattleContext cattle();
    }

    public record CattleResourceContext(CattleContext cattle) implements ResourceContext {
        public CattleResourceContext { if (cattle == null) throw new IllegalArgumentException("cattle is required"); }
    }

    public record ClinicalRecordRef(String recordId, String cattleId, String recordOriginFarmId) {
        public ClinicalRecordRef {
            requireIdentifier(recordId, "recordId");
            requireIdentifier(cattleId, "record cattleId");
            requireIdentifier(recordOriginFarmId, "recordOriginFarmId");
        }
    }

    public record HistoryRecordContext(CattleContext cattle, ClinicalRecordRef record) implements ResourceContext {
        public HistoryRecordContext { requireLinked(cattle, record); }
    }

    public record AcceptedReceiptContext(String receiptId, CattleContext cattle, ClinicalRecordRef record)
            implements ResourceContext {
        public AcceptedReceiptContext {
            requireIdentifier(receiptId, "receiptId");
            requireLinked(cattle, record);
        }
    }

    public record TargetReceiptContext(String receiptId, CattleContext cattle) implements ResourceContext {
        public TargetReceiptContext {
            requireIdentifier(receiptId, "receiptId");
            if (cattle == null) throw new IllegalArgumentException("cattle is required");
        }
    }

    public record PolicyGrant(
            String reference,
            PolicyRole role,
            String farmId,
            Set<String> barnIds,
            GrantStatus status,
            Instant validFrom,
            Instant validUntil,
            long version) {
        public PolicyGrant {
            requireIdentifier(reference, "grant reference");
            if (role == null || status == null || validFrom == null || barnIds == null || version < 0) {
                throw new IllegalArgumentException("Invalid policy grant");
            }
            requireIdentifier(farmId, "grant farmId");
            for (String barnId : barnIds) requireIdentifier(barnId, "grant barnId");
            barnIds = Set.copyOf(barnIds);
            if (validUntil != null && !validUntil.isAfter(validFrom)) {
                throw new IllegalArgumentException("validUntil must be after validFrom");
            }
        }
    }

    public record PolicySubject(String subjectId, List<PolicyGrant> grants) {
        public PolicySubject {
            requireIdentifier(subjectId, "subjectId");
            if (grants == null) throw new IllegalArgumentException("grants are required");
            grants = List.copyOf(grants);
        }
    }

    public record PolicyDecision(boolean allowed, String reason, String matchedGrantReference,
                                 Long matchedGrantVersion) {
        public PolicyDecision {
            requireIdentifier(reason, "decision reason");
            if (allowed != (matchedGrantReference != null) || allowed != (matchedGrantVersion != null)) {
                throw new IllegalArgumentException("Inconsistent policy decision");
            }
        }
    }

    public record TrustedPrincipal(String subjectId) {
        public TrustedPrincipal { requireIdentifier(subjectId, "subjectId"); }
    }

    public record HealthIntentSubmission(
            UUID intentId,
            UUID cattleId,
            String rfid,
            RecordType type,
            String canonicalPayloadJson,
            Instant occurredAt,
            String deviceRef,
            long expectedVersion,
            UUID scheduleId,
            UUID correctionOfRecordId,
            String correctionReason) {
        public HealthIntentSubmission {
            if (intentId == null || type == null || canonicalPayloadJson == null || occurredAt == null
                    || deviceRef == null || deviceRef.isBlank() || expectedVersion < 0) {
                throw new IllegalArgumentException("Invalid health intent");
            }
            boolean hasCorrectionId = correctionOfRecordId != null;
            boolean hasCorrectionReason = correctionReason != null && !correctionReason.isBlank();
            if (hasCorrectionId != hasCorrectionReason) {
                throw new IllegalArgumentException("Correction record and reason must be supplied together");
            }
            if (correctionReason != null) correctionReason = correctionReason.strip();
        }
    }

    public record ClinicalRecord(
            UUID recordId,
            UUID cattleId,
            long recordVersion,
            RecordType type,
            String payloadJson,
            Instant occurredAt,
            Instant recordedAt,
            String deviceRef,
            String originFarmId,
            UUID scheduleId,
            UUID correctionOfRecordId,
            String correctionReason) {
        public ClinicalRecord {
            if (recordId == null || cattleId == null || recordVersion < 1 || type == null || payloadJson == null
                    || occurredAt == null || recordedAt == null || deviceRef == null || originFarmId == null) {
                throw new IllegalArgumentException("Invalid clinical record");
            }
        }

        public ClinicalRecordRef policyRef() {
            return new ClinicalRecordRef(recordId.toString(), cattleId.toString(), originFarmId);
        }
    }

    public record HealthReceipt(
            UUID intentId,
            UUID cattleId,
            IntentStatus status,
            UUID recordId,
            long currentVersion,
            String reasonCode,
            boolean replayed,
            Instant receivedAt) {
        public HealthReceipt {
            if (intentId == null || cattleId == null || status == null || currentVersion < 0 || receivedAt == null) {
                throw new IllegalArgumentException("Invalid health receipt");
            }
        }

        public HealthReceipt asReplay() {
            return new HealthReceipt(intentId, cattleId, status, recordId, currentVersion,
                    reasonCode, true, receivedAt);
        }
    }

    public record ExistingIntent(String payloadHash, String actorSub, UUID cattleId,
                                 IntentStatus status, HealthReceipt receipt, ClinicalRecord record) {
        public ExistingIntent {
            if (payloadHash == null || actorSub == null || cattleId == null || status == null || receipt == null) {
                throw new IllegalArgumentException("Invalid stored intent");
            }
        }
    }

    public record ScheduleState(UUID scheduleId, UUID cattleId, String status, UUID effectiveRecordId) {}

    public record CattleView(CattleResource cattle) {}

    public record HistoryPage(UUID cattleId, List<ClinicalRecord> records, Long nextBeforeVersion) {
        public HistoryPage { records = List.copyOf(records); }
    }

    private static void requireLinked(CattleContext cattle, ClinicalRecordRef record) {
        if (cattle == null || record == null || !cattle.cattleId().equals(record.cattleId())) {
            throw new IllegalArgumentException("Clinical record must belong to target cattle");
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be nonblank and unpadded");
        }
    }
}
