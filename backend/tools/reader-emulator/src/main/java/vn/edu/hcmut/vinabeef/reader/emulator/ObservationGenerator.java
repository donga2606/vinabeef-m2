package vn.edu.hcmut.vinabeef.reader.emulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Builds the observation stream deterministically from options (IDs come from the supplied generator). */
final class ObservationGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneOffset FARM_OFFSET = ZoneOffset.ofHours(7);
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final Supplier<UUID> ids;

    ObservationGenerator(Supplier<UUID> ids) {
        this.ids = ids;
    }

    List<ObjectNode> generate(EmulatorOptions o) {
        List<ObjectNode> out = new ArrayList<>();
        Map<String, String> lastDirection = new HashMap<>();
        long seq = o.startSeq();
        Instant t = o.start();

        for (int i = 1; i <= o.count(); i++) {
            String tag = o.tags().get((i - 1) % o.tags().size());
            String dir = null;
            if (o.readerKind().equals("GATE")) {
                dir = o.direction() != null ? o.direction() : "IN".equals(lastDirection.get(tag)) ? "OUT" : "IN";
                lastDirection.put(tag, dir);
            }
            ObjectNode obs = null;
            // One logical passage may be seen several times by the antenna (multiplicity):
            // each extra read has its own observationId/seq, 100 ms apart in source time.
            for (int k = 0; k < o.readsPerPassage(); k++) {
                obs = MAPPER.createObjectNode();
                obs.put("schemaVersion", "tag-observation.v1");
                obs.put("observationId", ids.get().toString());
                obs.put("readerId", o.readerId());
                obs.put("readerKind", o.readerKind());
                obs.put("seq", seq);
                obs.put("tagId", tag);
                obs.put("observedAt", RFC3339.format(t.plusMillis(100L * k).atOffset(FARM_OFFSET)));
                if (dir != null) {
                    obs.put("direction", dir);
                }
                out.add(obs);
                seq++;
            }

            if (o.dupEvery() > 0 && i % o.dupEvery() == 0) {
                out.add(obs.deepCopy()); // retransmit: identical payload, same observationId
            }
            if (o.gapEvery() > 0 && i % o.gapEvery() == 0) {
                seq++; // missed read
            }
            t = t.plus(o.interval());
        }
        return out;
    }
}
