package vn.edu.hcmut.vinabeef.health.config;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * PHU-02 security-http: every {@code /api/**} request must carry a Keycloak access token that is
 * verified here (signature against the realm JWKS, issuer, expiry, authorized client). The verified
 * username becomes the Spring Security principal read by {@code SpringSecurityTrustedPrincipalProvider}.
 *
 * <p>Identity only. Token roles are deliberately ignored: authorization comes from current grants
 * evaluated per operation by {@code HealthAuthorization} (ADR-16).
 */
@Configuration
public class HealthSecurityConfiguration {

    @Bean
    SecurityFilterChain healthSecurityFilterChain(HttpSecurity http,
            JwtAuthenticationConverter healthJwtAuthenticationConverter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(healthJwtAuthenticationConverter)));
        return http.build();
    }

    /**
     * Keys are fetched from the internal JWKS URI (container network) on first use, so the service
     * starts even when Keycloak is not up yet; the issuer must still equal the public issuer.
     */
    @Bean
    JwtDecoder healthJwtDecoder(@Value("${health.security.oidc.jwk-set-uri}") String jwkSetUri,
                                @Value("${health.security.oidc.issuer}") String issuer,
                                @Value("${health.security.oidc.allowed-clients}") String[] allowedClients,
                                @Value("${health.security.oidc.principal-claim}") String principalClaim) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        Set<String> clients = Arrays.stream(allowedClients).map(String::trim).filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        decoder.setJwtValidator(tokenValidator(issuer, clients, principalClaim));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter healthJwtAuthenticationConverter(
            @Value("${health.security.oidc.principal-claim}") String principalClaim) {
        return authenticationConverter(principalClaim);
    }

    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, Set<String> allowedClients, String principalClaim) {
        OAuth2TokenValidator<Jwt> standard = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> client = authorizedParty(allowedClients);
        OAuth2TokenValidator<Jwt> principal = requiredClaim(principalClaim);
        return new DelegatingOAuth2TokenValidator<>(List.of(standard, client, principal));
    }

    static JwtAuthenticationConverter authenticationConverter(String principalClaim) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(principalClaim);
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of());
        return converter;
    }

    private static OAuth2TokenValidator<Jwt> authorizedParty(Set<String> allowedClients) {
        return jwt -> {
            String azp = jwt.getClaimAsString("azp");
            return azp != null && allowedClients.contains(azp)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                            "The token was not issued to an allowed VinaBeef client.", null));
        };
    }

    private static OAuth2TokenValidator<Jwt> requiredClaim(String claim) {
        return jwt -> {
            String value = jwt.getClaimAsString(claim);
            return value != null && !value.isBlank()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                            "The token has no " + claim + " claim.", null));
        };
    }
}
