package vn.edu.hcmut.vinabeef.edge.domain;

import java.util.UUID;

/** What a new gate observation means for the farm. */
public sealed interface Decision {

    /** Another read of a passage already committed (antenna multiplicity): no new movement, no count change. */
    record Merge(UUID passageId) implements Decision {
    }

    /** A real movement through the gate. {@code anomaly} = direction contradicts the known presence. */
    record NewPassage(Direction direction, Presence presenceAfter, boolean anomaly) implements Decision {
    }

    /** Older than the tag's latest passage: kept as evidence, needs review, never changes the count. */
    record Late() implements Decision {
    }
}
