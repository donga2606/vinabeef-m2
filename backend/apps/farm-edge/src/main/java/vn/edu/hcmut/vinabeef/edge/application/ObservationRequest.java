package vn.edu.hcmut.vinabeef.edge.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Inbound TagObservation v1 (contracts/events/tag-observation.v1.schema.json). */
public record ObservationRequest(
        String schemaVersion,
        UUID observationId,
        String readerId,
        String readerKind,
        Long seq,
        String tagId,
        OffsetDateTime observedAt,
        OffsetDateTime receivedAt,
        String direction) {
}
