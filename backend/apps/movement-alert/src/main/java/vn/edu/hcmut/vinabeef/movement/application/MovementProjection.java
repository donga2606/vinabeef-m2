package vn.edu.hcmut.vinabeef.movement.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies one event exactly once: dedupe marker and projection effect commit in the same transaction. */
@Service
public class MovementProjection {

    public enum Outcome { APPLIED, APPLIED_STALE, DUPLICATE }

    private final JdbcTemplate jdbc;

    public MovementProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Outcome apply(MovementEvent e, String payload) {
        int inserted = jdbc.update("""
                INSERT INTO received_event (event_id, farm_id, edge_seq, tag_id, direction, presence_after, anomaly,
                                            occurred_at, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING""",
                e.eventId(), e.farmId(), e.edgeSeq(), e.tagId(), e.direction(), e.presenceAfter(), e.anomaly(),
                e.occurredAt(), payload);
        if (inserted == 0) {
            return Outcome.DUPLICATE;
        }
        int updated = jdbc.update("""
                INSERT INTO tag_presence (farm_id, tag_id, presence, last_edge_seq, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (farm_id, tag_id) DO UPDATE
                  SET presence = EXCLUDED.presence, last_edge_seq = EXCLUDED.last_edge_seq, updated_at = now()
                  WHERE tag_presence.last_edge_seq < EXCLUDED.last_edge_seq""",
                e.farmId(), e.tagId(), e.presenceAfter(), e.edgeSeq());
        return updated == 1 ? Outcome.APPLIED : Outcome.APPLIED_STALE;
    }
}
