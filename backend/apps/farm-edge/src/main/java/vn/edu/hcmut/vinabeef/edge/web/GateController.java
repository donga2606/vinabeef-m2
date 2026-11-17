package vn.edu.hcmut.vinabeef.edge.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.vinabeef.edge.application.GateService;
import vn.edu.hcmut.vinabeef.edge.application.ObservationConflictException;
import vn.edu.hcmut.vinabeef.edge.application.ObservationRejectedException;
import vn.edu.hcmut.vinabeef.edge.application.ObservationRequest;
import vn.edu.hcmut.vinabeef.edge.application.Receipt;
import vn.edu.hcmut.vinabeef.edge.config.EdgeProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gate ingress (reader adapter / emulator on the farm LAN) and local read-only queries.
 * Prototype limitation: no machine authentication on this port; it is reachable only on farm-lan
 * and on host loopback for debugging.
 */
@RestController
@RequestMapping("/edge/v1")
public class GateController {

    private final GateService gate;
    private final JdbcTemplate jdbc;
    private final String farmId;

    public GateController(GateService gate, JdbcTemplate jdbc, EdgeProperties props) {
        this.gate = gate;
        this.jdbc = jdbc;
        this.farmId = props.farmId();
    }

    @PostMapping("/observations")
    public Receipt observe(@RequestBody ObservationRequest req) {
        return gate.ingest(req);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("farmId", farmId);
        s.putAll(jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM tag_presence WHERE presence = 'INSIDE') AS "insideCount",
                       (SELECT count(*) FROM passage)                               AS "passages",
                       (SELECT count(*) FROM passage WHERE anomaly)                 AS "anomalies",
                       (SELECT count(*) FROM observation)                           AS "observations",
                       (SELECT count(*) FROM observation WHERE outcome = 'MERGED')  AS "mergedReads",
                       (SELECT count(*) FROM observation WHERE outcome = 'LATE_NEEDS_REVIEW') AS "lateReads",
                       (SELECT coalesce(sum(to_seq - from_seq + 1), 0) FROM reader_gap) AS "missedReaderSeqs",
                       (SELECT coalesce(max(last_edge_seq), 0) FROM gate_state)      AS "lastEdgeSeq",
                       (SELECT count(*) FROM outbox WHERE sent_at IS NULL)           AS "outboxPending",
                       (SELECT count(*) FROM outbox WHERE sent_at IS NOT NULL)       AS "outboxSent"
                """));
        return s;
    }

    @GetMapping("/tags/{tagId}")
    public Map<String, Object> tag(@PathVariable String tagId) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("tagId", tagId);
        t.put("presence", jdbc.query("SELECT presence FROM tag_presence WHERE tag_id = ?",
                rs -> rs.next() ? rs.getString(1) : "OUTSIDE (never seen)", tagId));
        List<Map<String, Object>> passages = jdbc.queryForList("""
                SELECT p.passage_id AS "eventId", p.edge_seq AS "edgeSeq", p.direction, p.anomaly,
                       p.observation_count AS "reads", o.sent_at IS NOT NULL AS "relayed"
                FROM passage p JOIN outbox o ON o.event_id = p.passage_id
                WHERE p.tag_id = ? ORDER BY p.edge_seq""", tagId);
        t.put("passages", passages);
        return t;
    }

    @ExceptionHandler(ObservationRejectedException.class)
    ProblemDetail rejected(ObservationRejectedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(ObservationConflictException.class)
    ProblemDetail conflict(ObservationConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "body is not a readable TagObservation v1");
    }
}
