package vn.edu.hcmut.vinabeef.health.adapter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.TreeMap;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthIntentSubmission;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.RecordType;

public record HealthIntentRequest(
        @NotNull UUID intentId,
        UUID cattleId,
        @Size(min = 1, max = 128) String rfid,
        @NotNull RecordType type,
        @NotNull JsonNode payload,
        @NotNull Instant occurredAt,
        @NotBlank @Size(max = 120) String deviceRef,
        @NotNull @PositiveOrZero Long expectedVersion,
        UUID scheduleId,
        UUID correctionOfRecordId,
        @Size(min = 1, max = 500) String correctionReason) {

    @JsonAnySetter
    public void rejectUnknownProperty(String property, JsonNode value) {
        throw HealthException.invalid("Unknown health intent field.");
    }

    public HealthIntentSubmission toSubmission(ObjectMapper mapper) {
        if ((cattleId == null) == (rfid == null)) {
            throw HealthException.invalid("Supply exactly one of cattleId or rfid.");
        }
        if (payload == null || !payload.isObject()) {
            throw HealthException.invalid("payload must be a JSON object.");
        }
        if ((correctionOfRecordId == null) != (correctionReason == null || correctionReason.isBlank())) {
            throw HealthException.invalid("correctionOfRecordId and correctionReason must be supplied together.");
        }
        try {
            String canonicalPayload = mapper.writeValueAsString(canonical(payload, mapper));
            return new HealthIntentSubmission(intentId, cattleId, rfid == null ? null : rfid.strip(), type,
                    canonicalPayload, occurredAt, deviceRef.strip(), expectedVersion,
                    scheduleId, correctionOfRecordId, correctionReason);
        } catch (HealthException exception) {
            throw exception;
        } catch (Exception exception) {
            throw HealthException.invalid("payload must be valid JSON.");
        }
    }

    private static JsonNode canonical(JsonNode value, ObjectMapper mapper) {
        if (value.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> sorted.set(key, canonical(child, mapper)));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            value.forEach(child -> array.add(canonical(child, mapper)));
            return array;
        }
        return value.deepCopy();
    }
}
