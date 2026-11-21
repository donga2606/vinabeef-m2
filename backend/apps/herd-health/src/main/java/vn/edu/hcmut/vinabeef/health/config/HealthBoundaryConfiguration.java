package vn.edu.hcmut.vinabeef.health.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.edu.hcmut.vinabeef.health.application.port.out.AccessPolicyPort;
import vn.edu.hcmut.vinabeef.health.application.port.out.CurrentGrantContextProvider;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;

@Configuration
public class HealthBoundaryConfiguration {
    @Bean
    Clock healthClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(CurrentGrantContextProvider.class)
    CurrentGrantContextProvider unavailableGrantContextProvider() {
        return subjectId -> { throw HealthException.unavailable("GRANT_CONTEXT_UNAVAILABLE"); };
    }

    @Bean
    @ConditionalOnMissingBean(AccessPolicyPort.class)
    AccessPolicyPort unavailableAccessPolicy() {
        return (action, subject, resource, decisionTime) -> {
            throw HealthException.unavailable("POLICY_UNAVAILABLE");
        };
    }
}
