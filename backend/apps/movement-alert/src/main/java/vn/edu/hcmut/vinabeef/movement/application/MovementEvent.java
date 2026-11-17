package vn.edu.hcmut.vinabeef.movement.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/** contracts/events/movement-committed.v1.schema.json */
public record MovementEvent(
        String schemaVersion,
        UUID eventId,
        String farmId,
        Long edgeSeq,
        String tagId,
        String direction,
        String presenceAfter,
        Boolean anomaly,
        OffsetDateTime occurredAt,
        String readerId,
        UUID sourceObservationId,
        OffsetDateTime committedAt) {

    public void requireValid() {
        if (!"movement-committed.v1".equals(schemaVersion) || eventId == null || farmId == null || edgeSeq == null
                || edgeSeq < 1 || tagId == null || direction == null || presenceAfter == null || anomaly == null
                || occurredAt == null) {
            throw new IllegalArgumentException("not a valid movement-committed.v1 event");
        }
    }
}
