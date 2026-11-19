package vn.edu.hcmut.vinabeef.health.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResource;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ClinicalRecord;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ExistingIntent;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthIntentSubmission;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthReceipt;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ScheduleState;

public interface HealthRepository {
    Optional<CattleResource> findCattleByRfid(String rfid);
    Optional<CattleResource> findCattleById(UUID cattleId);
    Optional<CattleResource> lockCattle(UUID cattleId);
    Optional<ExistingIntent> findIntent(UUID intentId);
    boolean tryClaimIntent(HealthIntentSubmission submission, String payloadHash, String actorSub,
                           CattleResource cattle, Instant now);
    Optional<ClinicalRecord> findRecord(UUID recordId);
    Optional<ClinicalRecord> latestRecord(UUID cattleId);
    List<ClinicalRecord> history(UUID cattleId, long beforeVersion, int limit);
    Optional<ScheduleState> findSchedule(UUID scheduleId);
    Optional<ScheduleState> lockSchedule(UUID scheduleId);
    void completeIntent(HealthIntentSubmission submission, String payloadHash, String actorSub,
                        CattleResource submittedCattle, HealthReceipt receipt, int httpStatus,
                        ClinicalRecord newRecord, Instant now);
}
