package vn.edu.hcmut.vinabeef.health.adapter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ClinicalRecord;

import java.time.Instant;
import java.util.UUID;

public record HealthRecordResponse(
        UUID recordId,
        UUID cattleId,
        long recordVersion,
        String type,
        JsonNode payload,
        Instant occurredAt,
        Instant recordedAt,
        String originFarmId,
        UUID scheduleId,
        UUID correctionOfRecordId,
        String correctionReason) {

    static HealthRecordResponse from(ClinicalRecord record, ObjectMapper mapper) {
        try {
            return new HealthRecordResponse(record.recordId(), record.cattleId(), record.recordVersion(),
                    record.type().name(), mapper.readTree(record.payloadJson()), record.occurredAt(),
                    record.recordedAt(), record.originFarmId(), record.scheduleId(),
                    record.correctionOfRecordId(), record.correctionReason());
        } catch (Exception exception) {
            throw HealthException.unavailable("CLINICAL_RECORD_INVALID");
        }
    }
}
