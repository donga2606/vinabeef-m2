package vn.edu.hcmut.vinabeef.health.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import vn.edu.hcmut.vinabeef.health.adapter.security.ProblemJsonAuthenticationEntryPoint;

/**
 * Transport authentication only (who is calling). Authorization stays in the application layer:
 * HealthAuthorization loads current grants and asks the access-policy library per action/resource.
 * JWT roles are deliberately NOT mapped to authorities, so a token cannot grant clinical scope.
 */
@Configuration
@EnableWebSecurity
public class HealthSecurityConfiguration {

    @Bean
    SecurityFilterChain healthApiSecurity(HttpSecurity http,
                                          JwtAuthenticationConverter jwtAuthenticationConverter,
                                          ProblemJsonAuthenticationEntryPoint entryPoint) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint));
        return http.build();
    }

    /** Signature via JWKS (internal route), plus exp/nbf and iss == public issuer. */
    @Bean
    JwtDecoder healthJwtDecoder(@Value("${health.security.jwk-set-uri}") String jwkSetUri,
                                @Value("${health.security.public-issuer}") String publicIssuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(publicIssuer));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter healthJwtAuthenticationConverter(
            @Value("${health.security.principal-claim:preferred_username}") String principalClaim) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(principalClaim);
        converter.setJwtGrantedAuthoritiesConverter(jwt -> java.util.List.of());
        return converter;
    }
}
