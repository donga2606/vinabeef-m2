package vn.edu.hcmut.vinabeef.health.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/** PHU-02: token checks that run after the signature is verified. No network, no Spring context. */
class HealthSecurityConfigurationTest {
    private static final String ISSUER = "http://localhost:8081/realms/vinabeef";

    private final OAuth2TokenValidator<Jwt> validator = HealthSecurityConfiguration.tokenValidator(
            ISSUER, Set.of("vinabeef-mobile", "vinabeef-dev-cli"), "preferred_username");

    private static Jwt.Builder token() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .header("typ", "JWT")
                .issuer(ISSUER)
                .subject("6f1c7a52-0c9e-4b8e-9f43-000000000001")
                .claim("azp", "vinabeef-mobile")
                .claim("preferred_username", "bs.demo")
                .issuedAt(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(300));
    }

    @Test
    void acceptsRealmTokenIssuedToAnAllowedClient() {
        assertFalse(validator.validate(token().build()).hasErrors());
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        assertTrue(validator.validate(token().issuer("http://localhost:8081/realms/other").build()).hasErrors());
    }

    @Test
    void rejectsExpiredToken() {
        Instant now = Instant.now();
        Jwt expired = token().issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(600)).build();
        assertTrue(validator.validate(expired).hasErrors());
    }

    @Test
    void rejectsTokenIssuedToAnotherClient() {
        assertTrue(validator.validate(token().claim("azp", "account-console").build()).hasErrors());
    }

    @Test
    void rejectsTokenWithoutPrincipalClaim() {
        Jwt anonymous = token().claims(claims -> claims.remove("preferred_username")).build();
        assertTrue(validator.validate(anonymous).hasErrors());
    }

    @Test
    void principalIsTheUsernameAndTokenRolesGrantNothing() {
        Jwt jwt = token().claim("realm_access", Map.of("roles", List.of("SA", "FM"))).build();

        Authentication authentication = HealthSecurityConfiguration.authenticationConverter("preferred_username")
                .convert(jwt);

        assertEquals("bs.demo", authentication.getName());
        assertTrue(authentication.getAuthorities().isEmpty());
    }
}
