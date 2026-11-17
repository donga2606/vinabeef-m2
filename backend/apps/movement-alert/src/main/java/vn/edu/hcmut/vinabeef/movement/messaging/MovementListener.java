package vn.edu.hcmut.vinabeef.movement.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import vn.edu.hcmut.vinabeef.movement.application.MovementEvent;
import vn.edu.hcmut.vinabeef.movement.application.MovementProjection;
import vn.edu.hcmut.vinabeef.movement.config.AmqpTopology;
import vn.edu.hcmut.vinabeef.movement.lab.FaultSwitch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Manual ACK, only after the projection transaction has committed. A crash between commit and ACK
 * causes redelivery, which the dedupe marker turns into a no-op (DUPLICATE).
 * Unreadable events and conflicting edgeSeq go to the DLQ instead of looping.
 */
@Component
public class MovementListener {

    private static final Logger log = LoggerFactory.getLogger(MovementListener.class);

    private final MovementProjection projection;
    private final ObjectMapper mapper;
    private final FaultSwitch faults;

    public MovementListener(MovementProjection projection, ObjectMapper mapper, FaultSwitch faults) {
        this.projection = projection;
        this.mapper = mapper;
        this.faults = faults;
    }

    @RabbitListener(queues = AmqpTopology.QUEUE, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        MovementEvent event;
        try {
            event = mapper.readValue(payload, MovementEvent.class);
            event.requireValid();
        } catch (IOException | IllegalArgumentException e) {
            log.error("rejecting unreadable event to DLQ: {}", e.getMessage());
            channel.basicNack(tag, false, false);
            return;
        }
        try {
            MovementProjection.Outcome outcome = projection.apply(event, payload);
            log.info("event {} farm={} edgeSeq={} -> {}", event.eventId(), event.farmId(), event.edgeSeq(), outcome);
        } catch (DuplicateKeyException e) {
            log.error("edgeSeq {} of farm {} already used by another eventId -> DLQ", event.edgeSeq(), event.farmId());
            channel.basicNack(tag, false, false);
            return;
        } catch (RuntimeException e) {
            log.warn("transient failure for event {}, requeue: {}", event.eventId(), e.getMessage());
            channel.basicNack(tag, false, true);
            sleepQuietly();
            return;
        }
        if (faults.haltNow()) {
            log.error("LAB FAULT: halting after commit of {} and before ACK", event.eventId());
            Runtime.getRuntime().halt(137);
        }
        channel.basicAck(tag, false);
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
