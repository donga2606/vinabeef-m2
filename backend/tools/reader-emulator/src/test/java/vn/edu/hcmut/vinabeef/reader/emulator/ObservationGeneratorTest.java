package vn.edu.hcmut.vinabeef.reader.emulator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservationGeneratorTest {

    private final AtomicLong counter = new AtomicLong();
    private final ObservationGenerator generator = new ObservationGenerator(() -> new UUID(0, counter.incrementAndGet()));

    private List<ObjectNode> run(String... args) {
        return generator.generate(EmulatorOptions.parse(args));
    }

    @Test
    void gateDirectionAlternatesPerTagStartingWithIn() {
        List<ObjectNode> obs = run("--kind", "GATE", "--count", "4", "--tags", "A,B",
                "--start", "2026-11-17T02:00:00Z");
        assertEquals(List.of("IN", "IN", "OUT", "OUT"), obs.stream().map(o -> o.get("direction").asText()).toList());
        assertEquals(List.of("A", "B", "A", "B"), obs.stream().map(o -> o.get("tagId").asText()).toList());
    }

    @Test
    void mobileNeverCarriesDirection() {
        run("--kind", "MOBILE", "--count", "5").forEach(o -> assertFalse(o.has("direction")));
    }

    @Test
    void seqIsMonotonicAndGapSkipsOne() {
        List<ObjectNode> obs = run("--count", "5", "--start-seq", "10", "--gap-every", "2");
        assertEquals(List.of(10L, 11L, 13L, 14L, 16L), obs.stream().map(o -> o.get("seq").asLong()).toList());
    }

    @Test
    void retransmitRepeatsSameObservation() {
        List<ObjectNode> obs = run("--count", "4", "--dup-every", "2");
        assertEquals(6, obs.size());
        assertEquals(obs.get(1), obs.get(2));
        assertEquals(obs.get(4), obs.get(5));
        assertEquals(4, obs.stream().map(o -> o.get("observationId").asText()).distinct().count());
    }

    @Test
    void sourceTimeAdvancesByInterval() {
        List<ObjectNode> obs = run("--count", "2", "--interval-ms", "250", "--start", "2026-11-17T02:00:00Z");
        assertEquals("2026-11-17T09:00:00.000+07:00", obs.get(0).get("observedAt").asText());
        assertEquals("2026-11-17T09:00:00.250+07:00", obs.get(1).get("observedAt").asText());
    }

    @Test
    void readsPerPassageEmitsDistinctReadsWithSameTagAndDirection() {
        List<ObjectNode> obs = run("--count", "2", "--tags", "A,B", "--reads-per-passage", "3",
                "--start", "2026-11-17T02:00:00Z");
        assertEquals(6, obs.size());
        assertEquals(6, obs.stream().map(o -> o.get("observationId").asText()).distinct().count());
        assertEquals(List.of("A", "A", "A", "B", "B", "B"), obs.stream().map(o -> o.get("tagId").asText()).toList());
        obs.forEach(o -> assertEquals("IN", o.get("direction").asText()));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), obs.stream().map(o -> o.get("seq").asLong()).toList());
        assertEquals("2026-11-17T09:00:00.200+07:00", obs.get(2).get("observedAt").asText());
    }

    @Test
    void forcedDirectionAppliesToEveryRead() {
        run("--count", "4", "--tags", "A,B", "--direction", "OUT").forEach(o -> assertEquals("OUT", o.get("direction").asText()));
        assertThrows(IllegalArgumentException.class, () -> EmulatorOptions.parse("--kind", "MOBILE", "--direction", "IN"));
    }

    @Test
    void rejectsUnknownKind() {
        assertThrows(IllegalArgumentException.class, () -> EmulatorOptions.parse("--kind", "NFC"));
    }
}
