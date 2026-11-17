package vn.edu.hcmut.vinabeef.movement.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The consumer owns its queue, binding and dead-letter queue. */
@Configuration
public class AmqpTopology {

    public static final String EXCHANGE = "vinabeef.movement";
    public static final String QUEUE = "movement-alert.movement-committed";
    public static final String DLQ = QUEUE + ".dlq";

    @Bean
    TopicExchange movementExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue movementQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DLQ)
                .build();
    }

    @Bean
    Queue movementDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding movementBinding(Queue movementQueue, TopicExchange movementExchange) {
        return BindingBuilder.bind(movementQueue).to(movementExchange).with("movement.committed.#");
    }
}
