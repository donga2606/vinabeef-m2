package vn.edu.hcmut.vinabeef.movement.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only central projection queries (no auth yet: Phú's security-http is not wired). */
@RestController
@RequestMapping("/movement/v1/farms/{farmId}")
public class MovementQueryController {

    private final JdbcTemplate jdbc;

    public MovementQueryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@PathVariable String farmId) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("farmId", farmId);
        s.putAll(jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM tag_presence WHERE farm_id = ? AND presence = 'INSIDE') AS "insideCount",
                       count(*)                         AS "eventsReceived",
                       coalesce(max(edge_seq), 0)       AS "maxEdgeSeq",
                       coalesce(max(edge_seq), 0) - count(*) AS "missingEdgeSeqs",
                       count(*) FILTER (WHERE anomaly)  AS "anomalies"
                FROM received_event WHERE farm_id = ?""", farmId, farmId));
        return s;
    }

    @GetMapping("/tags/{tagId}")
    public Map<String, Object> tag(@PathVariable String farmId, @PathVariable String tagId) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("tagId", tagId);
        t.put("presence", jdbc.query("SELECT presence FROM tag_presence WHERE farm_id = ? AND tag_id = ?",
                rs -> rs.next() ? rs.getString(1) : "UNKNOWN", farmId, tagId));
        t.put("events", jdbc.queryForList("""
                SELECT event_id AS "eventId", edge_seq AS "edgeSeq", direction
                FROM received_event WHERE farm_id = ? AND tag_id = ? ORDER BY edge_seq""", farmId, tagId));
        return t;
    }
}
