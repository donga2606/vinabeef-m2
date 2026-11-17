package vn.edu.hcmut.vinabeef.edge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.edu.hcmut.vinabeef.edge.config.EdgeProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Relays committed outbox events to central over the WAN path (wan-proxy -> RabbitMQ), in edge_seq order.
 * A row is marked sent only after a positive publisher confirm with no return. A confirm means "the broker
 * has it", not "central applied it": movement-alert dedupes by eventId, so re-publishing after a crash
 * between confirm and marking is safe. On any failure the batch stops and the row stays pending.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final EdgeProperties.Relay cfg;
    private final String routingKey;

    public OutboxRelay(JdbcTemplate jdbc, RabbitTemplate rabbit, EdgeProperties props) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.cfg = props.relay();
        this.routingKey = "movement.committed." + props.farmId();
    }

    @Scheduled(fixedDelayString = "${edge.relay.interval-ms:500}")
    public void relayPending() {
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT event_id, payload FROM outbox WHERE sent_at IS NULL ORDER BY edge_seq LIMIT ?", cfg.batchSize());
        for (Map<String, Object> row : pending) {
            UUID eventId = (UUID) row.get("event_id");
            String error = publish(eventId, (String) row.get("payload"));
            if (error != null) {
                jdbc.update("UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE event_id = ?", error, eventId);
                log.warn("relay paused at event {}: {}", eventId, error);
                return; // keep order; retry on the next tick
            }
            jdbc.update("UPDATE outbox SET sent_at = now(), attempts = attempts + 1, last_error = NULL WHERE event_id = ?", eventId);
        }
    }

    /** @return null when confirmed by the broker, otherwise a short reason. */
    private String publish(UUID eventId, String payload) {
        Message msg = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setMessageId(eventId.toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        CorrelationData cd = new CorrelationData(eventId.toString());
        try {
            rabbit.send(cfg.exchange(), routingKey, msg, cd);
            CorrelationData.Confirm confirm = cd.getFuture().get(cfg.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (cd.getReturned() != null) {
                return "unroutable (no queue bound yet): " + cd.getReturned().getReplyText();
            }
            return confirm.isAck() ? null : "nack: " + confirm.getReason();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
