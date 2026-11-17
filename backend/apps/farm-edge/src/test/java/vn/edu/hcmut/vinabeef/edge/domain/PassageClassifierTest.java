package vn.edu.hcmut.vinabeef.edge.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PassageClassifierTest {

    private static final Instant T0 = Instant.parse("2026-11-17T02:00:00Z");
    private final PassageClassifier classifier = new PassageClassifier(Duration.ofSeconds(5));
    private final UUID passageId = UUID.randomUUID();

    @Test
    void firstInOfUnknownTagIsNormalPassage() {
        Decision d = classifier.decide(Direction.IN, T0, null, Presence.OUTSIDE);
        assertEquals(new Decision.NewPassage(Direction.IN, Presence.INSIDE, false), d);
    }

    @Test
    void sameDirectionWithinWindowMergesIntoSamePassage() {
        LastPassage last = new LastPassage(passageId, Direction.IN, T0);
        Decision d = classifier.decide(Direction.IN, T0.plusSeconds(5), last, Presence.INSIDE);
        assertEquals(new Decision.Merge(passageId), d);
    }

    @Test
    void genuineOutThenInIsNeverMergedEvenWithinWindow() {
        LastPassage out = new LastPassage(passageId, Direction.OUT, T0);
        Decision d = classifier.decide(Direction.IN, T0.plusSeconds(1), out, Presence.OUTSIDE);
        assertEquals(new Decision.NewPassage(Direction.IN, Presence.INSIDE, false), d);
    }

    @Test
    void sameDirectionAfterWindowIsNewPassageFlaggedAsAnomaly() {
        LastPassage last = new LastPassage(passageId, Direction.IN, T0);
        Decision d = classifier.decide(Direction.IN, T0.plusSeconds(6), last, Presence.INSIDE);
        assertEquals(new Decision.NewPassage(Direction.IN, Presence.INSIDE, true), d);
    }

    @Test
    void outOfTagNeverSeenIsAnomaly() {
        Decision d = classifier.decide(Direction.OUT, T0, null, Presence.OUTSIDE);
        assertEquals(new Decision.NewPassage(Direction.OUT, Presence.OUTSIDE, true), d);
    }

    @Test
    void readOlderThanLastPassageIsLate() {
        LastPassage last = new LastPassage(passageId, Direction.OUT, T0);
        assertInstanceOf(Decision.Late.class, classifier.decide(Direction.IN, T0.minusSeconds(6), last, Presence.OUTSIDE));
    }

    @Test
    void slightlyOlderReadOfSamePassageStillMerges() {
        LastPassage last = new LastPassage(passageId, Direction.IN, T0);
        assertEquals(new Decision.Merge(passageId), classifier.decide(Direction.IN, T0.minusSeconds(2), last, Presence.INSIDE));
    }

    @Test
    void olderReadWithOtherDirectionIsLateNotReordered() {
        LastPassage last = new LastPassage(passageId, Direction.OUT, T0);
        assertInstanceOf(Decision.Late.class, classifier.decide(Direction.IN, T0.minusSeconds(1), last, Presence.OUTSIDE));
    }
}
