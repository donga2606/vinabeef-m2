package vn.edu.hcmut.vinabeef.edge.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Turns one gate observation into a {@link Decision}. Pure function: no I/O, no framework.
 *
 * <ul>
 *   <li>Same tag + same direction within {@code debounce} of the last passage = the same passage (merge).</li>
 *   <li>A different direction is always a new passage, so a genuine OUT followed by IN is never merged.</li>
 *   <li>Same direction after the window = new passage flagged as anomaly (e.g. IN while INSIDE), never dropped.</li>
 *   <li>Late read (kept for review only, no count change): source time earlier than the last passage minus
 *       {@code debounce}, or earlier than the last passage with the other direction (would reorder history).</li>
 * </ul>
 */
public final class PassageClassifier {

    private final Duration debounce;

    public PassageClassifier(Duration debounce) {
        if (debounce.isNegative()) {
            throw new IllegalArgumentException("debounce must not be negative");
        }
        this.debounce = debounce;
    }

    public Decision decide(Direction direction, Instant observedAt, LastPassage last, Presence current) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(current, "current");

        if (last != null) {
            boolean older = observedAt.isBefore(last.observedAt());
            boolean tooOld = observedAt.isBefore(last.observedAt().minus(debounce));
            if (tooOld || (older && last.direction() != direction)) {
                return new Decision.Late();
            }
            boolean withinWindow = !observedAt.isAfter(last.observedAt().plus(debounce));
            if (last.direction() == direction && withinWindow) {
                return new Decision.Merge(last.passageId());
            }
        }
        boolean anomaly = current == direction.presenceAfter();
        return new Decision.NewPassage(direction, direction.presenceAfter(), anomaly);
    }
}
