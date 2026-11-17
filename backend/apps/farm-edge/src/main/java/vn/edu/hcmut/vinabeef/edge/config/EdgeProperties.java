package vn.edu.hcmut.vinabeef.edge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("edge")
public record EdgeProperties(String farmId, Duration debounce, Relay relay) {

    public record Relay(String exchange, int batchSize, Duration confirmTimeout) {
    }
}
