package vn.edu.hcmut.vinabeef.policy;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic authorization over verified, current, server-owned context. */
public final class ClinicalAuthorizationPolicy {
    /** APPLY_CORRECTION appends a linked correction to an accepted record (RBAC: origin-farm FM only). */
    public enum Action { VIEW_CATTLE, SUBMIT_HEALTH, READ_HISTORY, READ_RECEIPT, APPLY_CORRECTION }
    public enum Role { BS, FM, SA, EM }
    public enum GrantStatus { ACTIVE, REVOKED, DISABLED }
    public enum Reason {
        CURRENT_BARN, CURRENT_FARM, OWN_ORIGIN_RECORD, ORIGIN_FARM_CORRECTION,
        NO_MATCHING_GRANT, ACTION_CONTEXT_MISMATCH;

        private boolean permits() {
            return this == CURRENT_BARN || this == CURRENT_FARM || this == OWN_ORIGIN_RECORD
                    || this == ORIGIN_FARM_CORRECTION;
        }
    }

    /** One whole farm-scoped grant, never combined with another grant's scope. */
    public record Grant(String reference, Role role, String farmId, Set<String> barnIds,
                        GrantStatus status, Instant validFrom, Instant validUntil, long version) {
        public Grant {
            reference = identifier(reference, "grant reference");
            required(role, "role");
            farmId = identifier(farmId, "grant farmId");
            required(barnIds, "barnIds");
            for (String barn : barnIds) { identifier(barn, "barnId"); }
            barnIds = Set.copyOf(barnIds);
            required(status, "grant status");
            required(validFrom, "validFrom");
            if (validUntil != null && !validUntil.isAfter(validFrom)) {
                throw new IllegalArgumentException("validUntil must be after validFrom");
            }
            nonNegative(version, "grant version");
        }

        private boolean effectiveAt(Instant time) {
            return status == GrantStatus.ACTIVE && !time.isBefore(validFrom)
                    && (validUntil == null || time.isBefore(validUntil));
        }
    }

    /** Construction validates shape, not authentication or authority freshness. */
    public record Subject(String subjectId, List<Grant> grants) {
        public Subject {
            subjectId = identifier(subjectId, "subjectId");
            required(grants, "grants");
            Set<String> references = new HashSet<>();
            for (Grant grant : grants) {
                required(grant, "grant");
                if (!references.add(grant.reference())) {
                    throw new IllegalArgumentException("duplicate grant reference");
                }
            }
            grants = List.copyOf(grants);
        }
    }

    /** Current custody, resolved by Health; version is metadata, not a concurrency guard. */
    public record CattleContext(String cattleId, String farmId, String barnId, long custodyVersion) {
        public CattleContext {
            cattleId = identifier(cattleId, "cattleId");
            farmId = identifier(farmId, "cattle farmId");
            barnId = identifier(barnId, "cattle barnId");
            nonNegative(custodyVersion, "custodyVersion");
        }
    }

    /** Origin is immutable Health-owned first-acceptance farm, not request/device origin. */
    public record ClinicalRecord(String recordId, String cattleId, String recordOriginFarmId) {
        public ClinicalRecord {
            recordId = identifier(recordId, "recordId");
            cattleId = identifier(cattleId, "record cattleId");
            recordOriginFarmId = identifier(recordOriginFarmId, "recordOriginFarmId");
        }
    }

    public sealed interface ResourceContext
            permits CattleResource, HistoryRecord, AcceptedReceipt, TargetReceipt {
        CattleContext cattle();
    }

    public record CattleResource(CattleContext cattle) implements ResourceContext {
        public CattleResource { required(cattle, "cattle"); }
    }

    /** A single history record, never authorization of an unfiltered history collection. */
    public record HistoryRecord(CattleContext cattle, ClinicalRecord record) implements ResourceContext {
        public HistoryRecord { linked(cattle, record); }
    }

    public record AcceptedReceipt(String receiptId, CattleContext cattle, ClinicalRecord record)
            implements ResourceContext {
        public AcceptedReceipt {
            receiptId = identifier(receiptId, "receiptId");
            linked(cattle, record);
        }
    }

    /** Pending/failed receipt with no accepted record: current target scope only. */
    public record TargetReceipt(String receiptId, CattleContext cattle) implements ResourceContext {
        public TargetReceipt {
            receiptId = identifier(receiptId, "receiptId");
            required(cattle, "cattle");
        }
    }

    public record GrantReference(String reference, long version) {
        public GrantReference {
            reference = identifier(reference, "grant reference");
            nonNegative(version, "grant version");
        }
    }

    /** Denials contain no subject/resource content or positive matched grant. */
    public record Decision(boolean allowed, Reason reason, GrantReference matchedGrant) {
        public Decision {
            required(reason, "reason");
            if (allowed != reason.permits() || allowed != (matchedGrant != null)) {
                throw new IllegalArgumentException("inconsistent decision");
            }
        }
    }

    /** Server decisionTime is explicit; capturedAt must never be used to backdate authority. */
    public Decision evaluate(Action action, Subject subject, ResourceContext resource, Instant decisionTime) {
        required(action, "action");
        required(subject, "subject");
        required(resource, "resource");
        required(decisionTime, "decisionTime");
        if (!supports(action, resource)) {
            return new Decision(false, Reason.ACTION_CONTEXT_MISMATCH, null);
        }
        Grant best = null;
        Reason bestReason = null;
        for (Grant grant : subject.grants()) {
            if (!grant.effectiveAt(decisionTime)) { continue; }
            Reason reason = action == Action.APPLY_CORRECTION
                    ? matchCorrection(grant, (HistoryRecord) resource) : match(grant, resource);
            if (reason != null && (best == null || grant.reference().compareTo(best.reference()) < 0)) {
                best = grant;
                bestReason = reason;
            }
        }
        return best == null
                ? new Decision(false, Reason.NO_MATCHING_GRANT, null)
                : new Decision(true, bestReason, new GrantReference(best.reference(), best.version()));
    }

    private static boolean supports(Action action, ResourceContext resource) {
        return switch (action) {
            case VIEW_CATTLE, SUBMIT_HEALTH -> resource instanceof CattleResource;
            case READ_HISTORY, APPLY_CORRECTION -> resource instanceof HistoryRecord;
            case READ_RECEIPT -> resource instanceof AcceptedReceipt || resource instanceof TargetReceipt;
        };
    }

    /**
     * Only a Farm Manager of the record's origin farm may apply a correction. Current barn or
     * current farm custody alone is not enough (a BS, or a destination-farm FM after transfer).
     */
    private static Reason matchCorrection(Grant grant, HistoryRecord target) {
        return grant.role() == Role.FM && grant.farmId().equals(target.record().recordOriginFarmId())
                ? Reason.ORIGIN_FARM_CORRECTION : null;
    }

    private static Reason match(Grant grant, ResourceContext resource) {
        CattleContext cattle = resource.cattle();
        if (grant.role() == Role.BS && grant.farmId().equals(cattle.farmId())
                && grant.barnIds().contains(cattle.barnId())) {
            return Reason.CURRENT_BARN;
        }
        if (grant.role() == Role.FM) {
            if (grant.farmId().equals(cattle.farmId())) { return Reason.CURRENT_FARM; }
            ClinicalRecord record = switch (resource) {
                case HistoryRecord history -> history.record();
                case AcceptedReceipt receipt -> receipt.record();
                default -> null;
            };
            if (record != null && grant.farmId().equals(record.recordOriginFarmId())) {
                return Reason.OWN_ORIGIN_RECORD;
            }
        }
        return null;
    }

    private static void linked(CattleContext cattle, ClinicalRecord record) {
        required(cattle, "cattle");
        required(record, "record");
        if (!cattle.cattleId().equals(record.cattleId())) {
            throw new IllegalArgumentException("record does not belong to target cattle");
        }
    }

    private static <T> T required(T value, String field) {
        if (value == null) { throw new IllegalArgumentException(field + " is required"); }
        return value;
    }

    private static String identifier(String value, String field) {
        required(value, field);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be nonblank and unpadded");
        }
        return value;
    }

    private static void nonNegative(long value, String field) {
        if (value < 0) { throw new IllegalArgumentException(field + " must be nonnegative"); }
    }
}
