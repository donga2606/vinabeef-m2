package vn.edu.hcmut.vinabeef.health.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.vinabeef.health.application.port.out.HealthRepository;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResource;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ClinicalRecord;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ExistingIntent;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthIntentSubmission;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthReceipt;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.IntentStatus;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.RecordType;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.RegistrationStatus;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ScheduleState;

@Repository
public class JdbcHealthRepository implements HealthRepository {
    private static final String CATTLE_SELECT = """
            SELECT c.cattle_id, c.rfid, c.display_name, c.registration_status,
                   c.resource_version, cc.custody_version, cc.farm_id, f.farm_name, cc.barn_id
            FROM cattle c
            JOIN cattle_custody cc ON cc.cattle_id = c.cattle_id
            JOIN farm f ON f.farm_id = cc.farm_id
            """;
    private static final String RECORD_SELECT = """
            SELECT record_id, cattle_id, record_version, record_type, payload::text AS payload_json,
                   occurred_at, recorded_at, device_ref, origin_farm_id, schedule_id,
                   correction_of_record_id, correction_reason
            FROM clinical_record
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcHealthRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CattleResource> findCattleByRfid(String rfid) {
        return jdbc.sql(CATTLE_SELECT + " WHERE c.rfid = :rfid AND c.registration_status <> 'UNKNOWN'")
                .param("rfid", rfid)
                .query(this::mapCattle)
                .optional();
    }

    @Override
    public Optional<CattleResource> findCattleById(UUID cattleId) {
        return jdbc.sql(CATTLE_SELECT + " WHERE c.cattle_id = :cattleId AND c.registration_status <> 'UNKNOWN'")
                .param("cattleId", cattleId)
                .query(this::mapCattle)
                .optional();
    }

    @Override
    public Optional<CattleResource> lockCattle(UUID cattleId) {
        return jdbc.sql(CATTLE_SELECT + " WHERE c.cattle_id = :cattleId AND c.registration_status <> 'UNKNOWN' FOR UPDATE OF c, cc")
                .param("cattleId", cattleId)
                .query(this::mapCattle)
                .optional();
    }

    @Override
    public Optional<ExistingIntent> findIntent(UUID intentId) {
        IntentRow row = jdbc.sql("""
                SELECT payload_hash, actor_sub, cattle_id, status
                FROM health_intent WHERE intent_id = :intentId
                """).param("intentId", intentId)
                .query((rs, ignored) -> new IntentRow(rs.getString("payload_hash").stripTrailing(),
                        rs.getString("actor_sub"), rs.getObject("cattle_id", UUID.class),
                        IntentStatus.valueOf(rs.getString("status"))))
                .optional().orElse(null);
        if (row == null) return Optional.empty();

        HealthReceipt receipt = jdbc.sql("SELECT response_json::text FROM operation_receipt WHERE intent_id = :intentId")
                .param("intentId", intentId)
                .query((rs, ignored) -> readJson(rs.getString(1), HealthReceipt.class))
                .optional().orElseThrow(() -> HealthException.unavailable("RECEIPT_STORE_INCONSISTENT"));
        ClinicalRecord record = findRecordByIntent(intentId).orElse(null);
        return Optional.of(new ExistingIntent(row.payloadHash(), row.actorSub(), row.cattleId(),
                row.status(), receipt, record));
    }

    @Override
    public boolean tryClaimIntent(HealthIntentSubmission submission, String payloadHash, String actorSub,
                                  CattleResource cattle, Instant now) {
        String payload = submittedPayload(submission);
        return jdbc.sql("""
                INSERT INTO health_intent (
                    intent_id, cattle_id, submitted_farm_id, submitted_barn_id, actor_sub,
                    payload_hash, submitted_payload, expected_version, schedule_id,
                    correction_of_record_id, correction_reason, status, created_at
                ) VALUES (
                    :intentId, :cattleId, :farmId, :barnId, :actorSub,
                    :payloadHash, CAST(:payload AS jsonb), :expectedVersion, :scheduleId,
                    :correctionOf, :correctionReason, 'PROCESSING', :createdAt
                ) ON CONFLICT (intent_id) DO NOTHING
                RETURNING intent_id
                """)
                .param("intentId", submission.intentId())
                .param("cattleId", cattle.cattleId())
                .param("farmId", cattle.farmId())
                .param("barnId", cattle.barnId())
                .param("actorSub", actorSub)
                .param("payloadHash", payloadHash)
                .param("payload", payload)
                .param("expectedVersion", submission.expectedVersion())
                .param("scheduleId", submission.scheduleId())
                .param("correctionOf", submission.correctionOfRecordId())
                .param("correctionReason", submission.correctionReason())
                .param("createdAt", now.atOffset(ZoneOffset.UTC))
                .query((rs, ignored) -> rs.getObject(1, UUID.class))
                .optional()
                .isPresent();
    }

    @Override
    public Optional<ClinicalRecord> findRecord(UUID recordId) {
        return jdbc.sql(RECORD_SELECT + " WHERE record_id = :recordId")
                .param("recordId", recordId)
                .query(this::mapRecord)
                .optional();
    }

    @Override
    public Optional<ClinicalRecord> latestRecord(UUID cattleId) {
        return jdbc.sql(RECORD_SELECT + " WHERE cattle_id = :cattleId ORDER BY record_version DESC LIMIT 1")
                .param("cattleId", cattleId)
                .query(this::mapRecord)
                .optional();
    }

    @Override
    public List<ClinicalRecord> history(UUID cattleId, long beforeVersion, int limit) {
        return jdbc.sql(RECORD_SELECT + " WHERE cattle_id = :cattleId AND record_version < :beforeVersion "
                        + "ORDER BY record_version DESC LIMIT :limit")
                .param("cattleId", cattleId)
                .param("beforeVersion", beforeVersion)
                .param("limit", limit)
                .query(this::mapRecord)
                .list();
    }

    @Override
    public Optional<ScheduleState> findSchedule(UUID scheduleId) {
        return scheduleQuery(scheduleId, false);
    }

    @Override
    public Optional<ScheduleState> lockSchedule(UUID scheduleId) {
        return scheduleQuery(scheduleId, true);
    }

    private Optional<ScheduleState> scheduleQuery(UUID scheduleId, boolean lock) {
        String sql = """
                SELECT s.schedule_id, s.cattle_id, s.status, sc.effective_record_id
                FROM vaccination_schedule s
                LEFT JOIN schedule_completion sc ON sc.schedule_id = s.schedule_id
                WHERE s.schedule_id = :scheduleId
                """ + (lock ? " FOR UPDATE OF s" : "");
        return jdbc.sql(sql).param("scheduleId", scheduleId)
                .query((rs, ignored) -> new ScheduleState(rs.getObject("schedule_id", UUID.class),
                        rs.getObject("cattle_id", UUID.class), rs.getString("status"),
                        rs.getObject("effective_record_id", UUID.class)))
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeIntent(HealthIntentSubmission submission, String payloadHash, String actorSub,
                               CattleResource submittedCattle, HealthReceipt receipt, int httpStatus,
                               ClinicalRecord newRecord, Instant now) {
        OffsetDateTime databaseTime = now.atOffset(ZoneOffset.UTC);
        if (newRecord != null) insertClinicalRecord(submission.intentId(), newRecord);

        if (newRecord != null && newRecord.scheduleId() != null) {
            if (newRecord.correctionOfRecordId() == null) {
                jdbc.sql("""
                        INSERT INTO schedule_completion (schedule_id, effective_record_id, completed_at)
                        VALUES (:scheduleId, :recordId, :completedAt)
                        """).param("scheduleId", newRecord.scheduleId())
                        .param("recordId", newRecord.recordId())
                        .param("completedAt", databaseTime)
                        .update();
            } else {
                int updated = jdbc.sql("""
                        UPDATE schedule_completion
                        SET effective_record_id = :recordId, completed_at = :completedAt
                        WHERE schedule_id = :scheduleId AND effective_record_id = :previousRecordId
                        """).param("recordId", newRecord.recordId())
                        .param("completedAt", databaseTime)
                        .param("scheduleId", newRecord.scheduleId())
                        .param("previousRecordId", newRecord.correctionOfRecordId())
                        .update();
                if (updated != 1) throw HealthException.conflict("CORRECTION_TARGET_CHANGED",
                        "The scheduled dose changed while the correction was being saved.");
            }
        }

        if (newRecord != null) {
            int updated = jdbc.sql("UPDATE cattle SET resource_version = :version WHERE cattle_id = :cattleId")
                    .param("version", receipt.currentVersion())
                    .param("cattleId", newRecord.cattleId())
                    .update();
            if (updated != 1) throw HealthException.notFound("cattle");
        }

        jdbc.sql("UPDATE health_intent SET status = :status WHERE intent_id = :intentId")
                .param("status", receipt.status().name())
                .param("intentId", submission.intentId())
                .update();

        jdbc.sql("""
                INSERT INTO operation_receipt (intent_id, http_status, response_json, created_at)
                VALUES (:intentId, :httpStatus, CAST(:response AS jsonb), :createdAt)
                """)
                .param("intentId", submission.intentId())
                .param("httpStatus", httpStatus)
                .param("response", writeJson(receipt))
                .param("createdAt", databaseTime)
                .update();

        UUID evidenceId = stableId("health-source-evidence:" + submission.intentId());
        jdbc.sql("""
                INSERT INTO source_evidence (
                    evidence_id, intent_id, actor_sub, cattle_id, farm_id, barn_id,
                    action, payload_hash, outcome, captured_at
                ) VALUES (
                    :evidenceId, :intentId, :actorSub, :cattleId, :farmId, :barnId,
                    'HEALTH_INTENT_OUTCOME', :payloadHash, :outcome, :capturedAt
                )
                """)
                .param("evidenceId", evidenceId)
                .param("intentId", submission.intentId())
                .param("actorSub", actorSub)
                .param("cattleId", submittedCattle.cattleId())
                .param("farmId", submittedCattle.farmId())
                .param("barnId", submittedCattle.barnId())
                .param("payloadHash", payloadHash)
                .param("outcome", receipt.status().name())
                .param("capturedAt", databaseTime)
                .update();

        UUID eventId = stableId("health-audit-event:" + submission.intentId());
        jdbc.sql("""
                INSERT INTO health_audit_outbox (event_id, intent_id, event_type, payload, created_at)
                VALUES (:eventId, :intentId, 'health-intent.outcome.v1', CAST(:payload AS jsonb), :createdAt)
                """)
                .param("eventId", eventId)
                .param("intentId", submission.intentId())
                .param("payload", auditPayload(eventId, actorSub, submittedCattle, payloadHash, receipt, newRecord))
                .param("createdAt", databaseTime)
                .update();
    }

    private void insertClinicalRecord(UUID intentId, ClinicalRecord record) {
        jdbc.sql("""
                INSERT INTO clinical_record (
                    record_id, cattle_id, intent_id, record_version, record_type, payload,
                    occurred_at, recorded_at, device_ref, origin_farm_id, schedule_id,
                    correction_of_record_id, correction_reason
                ) VALUES (
                    :recordId, :cattleId, :intentId, :version, :type, CAST(:payload AS jsonb),
                    :occurredAt, :recordedAt, :deviceRef, :originFarmId, :scheduleId,
                    :correctionOf, :correctionReason
                )
                """)
                .param("recordId", record.recordId())
                .param("cattleId", record.cattleId())
                .param("intentId", intentId)
                .param("version", record.recordVersion())
                .param("type", record.type().name())
                .param("payload", record.payloadJson())
                .param("occurredAt", record.occurredAt().atOffset(ZoneOffset.UTC))
                .param("recordedAt", record.recordedAt().atOffset(ZoneOffset.UTC))
                .param("deviceRef", record.deviceRef())
                .param("originFarmId", record.originFarmId())
                .param("scheduleId", record.scheduleId())
                .param("correctionOf", record.correctionOfRecordId())
                .param("correctionReason", record.correctionReason())
                .update();
    }

    private Optional<ClinicalRecord> findRecordByIntent(UUID intentId) {
        return jdbc.sql(RECORD_SELECT + " WHERE intent_id = :intentId")
                .param("intentId", intentId)
                .query(this::mapRecord)
                .optional();
    }

    private CattleResource mapCattle(ResultSet rs, int ignored) throws SQLException {
        return new CattleResource(rs.getObject("cattle_id", UUID.class), rs.getString("rfid"),
                rs.getString("display_name"), RegistrationStatus.valueOf(rs.getString("registration_status")),
                rs.getString("farm_id"), rs.getString("farm_name"), rs.getString("barn_id"),
                rs.getLong("resource_version"), rs.getLong("custody_version"));
    }

    private ClinicalRecord mapRecord(ResultSet rs, int ignored) throws SQLException {
        return new ClinicalRecord(rs.getObject("record_id", UUID.class), rs.getObject("cattle_id", UUID.class),
                rs.getLong("record_version"), RecordType.valueOf(rs.getString("record_type")),
                rs.getString("payload_json"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(), rs.getString("device_ref"),
                rs.getString("origin_farm_id"), rs.getObject("schedule_id", UUID.class),
                rs.getObject("correction_of_record_id", UUID.class), rs.getString("correction_reason"));
    }

    private String submittedPayload(HealthIntentSubmission submission) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("intentId", submission.intentId().toString());
            if (submission.cattleId() != null) node.put("cattleId", submission.cattleId().toString());
            if (submission.rfid() != null) node.put("rfid", submission.rfid());
            node.put("type", submission.type().name());
            JsonNode payload = objectMapper.readTree(submission.canonicalPayloadJson());
            node.set("payload", payload);
            node.put("occurredAt", submission.occurredAt().toString());
            node.put("deviceRef", submission.deviceRef());
            node.put("expectedVersion", submission.expectedVersion());
            if (submission.scheduleId() != null) node.put("scheduleId", submission.scheduleId().toString());
            if (submission.correctionOfRecordId() != null) {
                node.put("correctionOfRecordId", submission.correctionOfRecordId().toString());
                node.put("correctionReason", submission.correctionReason());
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw HealthException.invalid("The health intent payload must be valid JSON.");
        }
    }

    private String auditPayload(UUID eventId, String actorSub, CattleResource cattle, String payloadHash,
                                HealthReceipt receipt, ClinicalRecord record) {
        return writeJson(Map.ofEntries(
                Map.entry("eventId", eventId),
                Map.entry("intentId", receipt.intentId()),
                Map.entry("actorSub", actorSub),
                Map.entry("cattleId", cattle.cattleId()),
                Map.entry("farmId", cattle.farmId()),
                Map.entry("barnId", cattle.barnId()),
                Map.entry("payloadHash", payloadHash),
                Map.entry("outcome", receipt.status()),
                Map.entry("recordId", record == null ? "" : record.recordId().toString()),
                Map.entry("reasonCode", receipt.reasonCode() == null ? "" : receipt.reasonCode()),
                Map.entry("receivedAt", receipt.receivedAt())));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw HealthException.unavailable("JSON_SERIALIZATION_FAILED");
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw HealthException.unavailable("RECEIPT_STORE_INVALID");
        }
    }

    private UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private record IntentRow(String payloadHash, String actorSub, UUID cattleId, IntentStatus status) {}
}
