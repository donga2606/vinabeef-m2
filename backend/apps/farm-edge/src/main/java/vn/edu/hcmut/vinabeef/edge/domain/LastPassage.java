package vn.edu.hcmut.vinabeef.edge.domain;

import java.time.Instant;
import java.util.UUID;

/** The most recent committed passage of a tag, as seen by the classifier. */
public record LastPassage(UUID passageId, Direction direction, Instant observedAt) {
}
