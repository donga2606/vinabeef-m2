package vn.edu.hcmut.vinabeef.edge.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.edu.hcmut.vinabeef.edge.domain.PassageClassifier;

@Configuration
public class EdgeConfig {

    @Bean
    PassageClassifier passageClassifier(EdgeProperties props) {
        return new PassageClassifier(props.debounce());
    }

    /** Edge only declares the exchange it publishes to; queues/bindings are owned by the consumer (movement-alert). */
    @Bean
    TopicExchange movementExchange(EdgeProperties props) {
        return new TopicExchange(props.relay().exchange(), true, false);
    }
}
