package vn.edu.hcmut.vinabeef.edge.application;

import java.util.UUID;

/**
 * Local gate outcome, returned once the observation is durably committed at the edge.
 * It is NOT a central acknowledgement: relay to central happens later through the outbox.
 */
public record Receipt(UUID observationId, String outcome, UUID passageId, Long edgeSeq, boolean anomaly,
                      long insideCount, boolean replayed) {
}
