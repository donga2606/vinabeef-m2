package vn.edu.hcmut.vinabeef.edge.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.vinabeef.edge.config.EdgeProperties;
import vn.edu.hcmut.vinabeef.edge.domain.Decision;
import vn.edu.hcmut.vinabeef.edge.domain.Direction;
import vn.edu.hcmut.vinabeef.edge.domain.LastPassage;
import vn.edu.hcmut.vinabeef.edge.domain.PassageClassifier;
import vn.edu.hcmut.vinabeef.edge.domain.Presence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Gate local core: one observation in, one durable local outcome out.
 * Observation evidence, passage, tag presence (count) and outbox event are written in ONE transaction,
 * with no call to central on this path, so a WAN outage cannot block or lose a local outcome.
 */
@Service
public class GateService {

    /** Always prints seconds (OffsetDateTime.toString() drops ":00", which is not RFC 3339). */
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final JdbcTemplate jdbc;
    private final PassageClassifier classifier;
    private final ObjectMapper mapper;
    private final String farmId;

    public GateService(JdbcTemplate jdbc, PassageClassifier classifier, ObjectMapper mapper, EdgeProperties props) {
        this.jdbc = jdbc;
        this.classifier = classifier;
        this.mapper = mapper;
        this.farmId = props.farmId();
    }

    @Transactional
    public Receipt ingest(ObservationRequest req) {
        validate(req);
        Direction direction = Direction.valueOf(req.direction());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Serialise gate processing for this farm and get the current edge sequence.
        jdbc.update("INSERT INTO gate_state (farm_id) VALUES (?) ON CONFLICT DO NOTHING", farmId);
        long lastEdgeSeq = jdbc.queryForObject(
                "SELECT last_edge_seq FROM gate_state WHERE farm_id = ? FOR UPDATE", Long.class, farmId);

        Receipt replay = replayIfKnown(req);
        if (replay != null) {
            return replay;
        }
        trackReaderSeq(req.readerId(), req.seq());

        LastPassage last = jdbc.query(
                "SELECT passage_id, direction, observed_at FROM passage WHERE tag_id = ? ORDER BY edge_seq DESC LIMIT 1",
                rs -> rs.next() ? new LastPassage(rs.getObject(1, UUID.class), Direction.valueOf(rs.getString(2)),
                        rs.getObject(3, OffsetDateTime.class).toInstant()) : null,
                req.tagId());
        Presence current = jdbc.query("SELECT presence FROM tag_presence WHERE tag_id = ?",
                rs -> rs.next() ? Presence.valueOf(rs.getString(1)) : Presence.OUTSIDE, req.tagId());

        Decision decision = classifier.decide(direction, req.observedAt().toInstant(), last, current);
        return switch (decision) {
            case Decision.Merge m -> {
                jdbc.update("UPDATE passage SET observation_count = observation_count + 1 WHERE passage_id = ?", m.passageId());
                insertObservation(req, now, "MERGED", m.passageId());
                Long seq = jdbc.queryForObject("SELECT edge_seq FROM passage WHERE passage_id = ?", Long.class, m.passageId());
                yield new Receipt(req.observationId(), "MERGED", m.passageId(), seq, false, insideCount(), false);
            }
            case Decision.Late l -> {
                insertObservation(req, now, "LATE_NEEDS_REVIEW", null);
                yield new Receipt(req.observationId(), "LATE_NEEDS_REVIEW", null, null, false, insideCount(), false);
            }
            case Decision.NewPassage p -> {
                long edgeSeq = lastEdgeSeq + 1;
                UUID passageId = UUID.randomUUID();
                jdbc.update("UPDATE gate_state SET last_edge_seq = ? WHERE farm_id = ?", edgeSeq, farmId);
                jdbc.update("""
                        INSERT INTO passage (passage_id, edge_seq, farm_id, tag_id, direction, presence_after, anomaly,
                                             observed_at, reader_id, first_observation_id, committed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        passageId, edgeSeq, farmId, req.tagId(), p.direction().name(), p.presenceAfter().name(),
                        p.anomaly(), req.observedAt(), req.readerId(), req.observationId(), now);
                jdbc.update("""
                        INSERT INTO tag_presence (tag_id, presence, last_passage_id, updated_at) VALUES (?, ?, ?, ?)
                        ON CONFLICT (tag_id) DO UPDATE
                          SET presence = EXCLUDED.presence, last_passage_id = EXCLUDED.last_passage_id,
                              updated_at = EXCLUDED.updated_at""",
                        req.tagId(), p.presenceAfter().name(), passageId, now);
                insertObservation(req, now, "NEW_PASSAGE", passageId);
                jdbc.update("INSERT INTO outbox (event_id, edge_seq, payload, created_at) VALUES (?, ?, ?, ?)",
                        passageId, edgeSeq, movementEvent(passageId, edgeSeq, req, p, now), now);
                yield new Receipt(req.observationId(), "NEW_PASSAGE", passageId, edgeSeq, p.anomaly(), insideCount(), false);
            }
        };
    }

    /** Same observationId again (reader retransmit / client retry): return the original outcome, change nothing. */
    private Receipt replayIfKnown(ObservationRequest req) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT o.reader_id, o.seq, o.tag_id, o.direction, o.observed_at, o.outcome, o.passage_id,
                       p.edge_seq, p.anomaly
                FROM observation o LEFT JOIN passage p ON p.passage_id = o.passage_id
                WHERE o.observation_id = ?""", req.observationId());
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.getFirst();
        boolean same = Objects.equals(r.get("reader_id"), req.readerId())
                && Objects.equals(((Number) r.get("seq")).longValue(), req.seq())
                && Objects.equals(r.get("tag_id"), req.tagId())
                && Objects.equals(r.get("direction"), req.direction())
                && ((java.sql.Timestamp) r.get("observed_at")).toInstant().equals(req.observedAt().toInstant());
        if (!same) {
            throw new ObservationConflictException("observationId " + req.observationId() + " was already used with different content");
        }
        Number edgeSeq = (Number) r.get("edge_seq");
        return new Receipt(req.observationId(), (String) r.get("outcome"), (UUID) r.get("passage_id"),
                edgeSeq == null ? null : edgeSeq.longValue(), Boolean.TRUE.equals(r.get("anomaly")), insideCount(), true);
    }

    private void trackReaderSeq(String readerId, long seq) {
        Long lastSeq = jdbc.query("SELECT last_seq FROM reader_state WHERE reader_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, readerId);
        if (lastSeq == null) {
            jdbc.update("INSERT INTO reader_state (reader_id, last_seq) VALUES (?, ?)", readerId, seq);
            return;
        }
        if (seq > lastSeq + 1) {
            jdbc.update("INSERT INTO reader_gap (reader_id, from_seq, to_seq) VALUES (?, ?, ?)", readerId, lastSeq + 1, seq - 1);
        }
        if (seq > lastSeq) {
            jdbc.update("UPDATE reader_state SET last_seq = ? WHERE reader_id = ?", seq, readerId);
        }
    }

    private void insertObservation(ObservationRequest req, OffsetDateTime now, String outcome, UUID passageId) {
        jdbc.update("""
                INSERT INTO observation (observation_id, reader_id, seq, tag_id, direction, observed_at, received_at,
                                         outcome, passage_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                req.observationId(), req.readerId(), req.seq(), req.tagId(), req.direction(), req.observedAt(),
                req.receivedAt() != null ? req.receivedAt() : now, outcome, passageId);
    }

    private long insideCount() {
        return jdbc.queryForObject("SELECT count(*) FROM tag_presence WHERE presence = 'INSIDE'", Long.class);
    }

    String movementEvent(UUID passageId, long edgeSeq, ObservationRequest req, Decision.NewPassage p, OffsetDateTime now) {
        ObjectNode e = mapper.createObjectNode();
        e.put("schemaVersion", "movement-committed.v1");
        e.put("eventId", passageId.toString());
        e.put("farmId", farmId);
        e.put("edgeSeq", edgeSeq);
        e.put("tagId", req.tagId());
        e.put("direction", p.direction().name());
        e.put("presenceAfter", p.presenceAfter().name());
        e.put("anomaly", p.anomaly());
        e.put("occurredAt", RFC3339.format(req.observedAt()));
        e.put("readerId", req.readerId());
        e.put("sourceObservationId", req.observationId().toString());
        e.put("committedAt", RFC3339.format(now));
        try {
            return mapper.writeValueAsString(e);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void validate(ObservationRequest req) {
        if (!"tag-observation.v1".equals(req.schemaVersion())) {
            throw new ObservationRejectedException("schemaVersion must be tag-observation.v1");
        }
        if (!"GATE".equals(req.readerKind())) {
            throw new ObservationRejectedException("only GATE readers may post to the gate (a mobile scan is not a passage)");
        }
        if (req.observationId() == null || req.readerId() == null || req.seq() == null || req.seq() < 1
                || req.tagId() == null || req.tagId().isBlank() || req.observedAt() == null) {
            throw new ObservationRejectedException("observationId, readerId, seq>=1, tagId and observedAt are required");
        }
        if (!"IN".equals(req.direction()) && !"OUT".equals(req.direction())) {
            throw new ObservationRejectedException("GATE observation needs direction IN or OUT");
        }
    }
}
