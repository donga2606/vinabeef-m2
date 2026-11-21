package vn.edu.hcmut.vinabeef.health.adapter.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import vn.edu.hcmut.vinabeef.health.application.port.out.CurrentGrantContextProvider;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.GrantStatus;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyGrant;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyRole;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicySubject;

/** Demo-only bridge. It re-reads the fixture on each request and is disabled by default. */
@Component
@ConditionalOnProperty(name = "health.authorization.fixture-enabled", havingValue = "true")
public class JsonFixtureCurrentGrantContextProvider implements CurrentGrantContextProvider {
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String fixtureLocation;

    public JsonFixtureCurrentGrantContextProvider(ResourceLoader resourceLoader, ObjectMapper objectMapper,
            @Value("${health.authorization.fixture-location:classpath:authorization-grants.demo.json}") String fixtureLocation) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.fixtureLocation = fixtureLocation;
    }

    @Override
    public PolicySubject loadCurrent(String verifiedSubjectId) {
        try {
            Resource resource = resourceLoader.getResource(fixtureLocation);
            JsonNode document;
            try (InputStream input = resource.getInputStream()) {
                document = objectMapper.readTree(input);
            }
            JsonNode subjects = document.path("subjects");
            if (!subjects.isArray()) throw new IllegalArgumentException("subjects must be an array");
            PolicySubject matchedSubject = null;
            for (JsonNode subject : subjects) {
                if (!verifiedSubjectId.equals(subject.path("subjectId").asText())) continue;
                if (matchedSubject != null) throw new IllegalArgumentException("duplicate subject fixture");
                JsonNode grants = subject.path("grants");
                if (!grants.isArray()) throw new IllegalArgumentException("grants must be an array");
                List<PolicyGrant> result = new ArrayList<>();
                Set<String> references = new HashSet<>();
                for (JsonNode grant : grants) {
                    Set<String> barns = new HashSet<>();
                    JsonNode barnIds = grant.path("barnIds");
                    if (!barnIds.isArray()) throw new IllegalArgumentException("barnIds must be an array");
                    barnIds.forEach(value -> barns.add(value.asText()));
                    PolicyGrant parsed = new PolicyGrant(
                            grant.path("reference").asText(),
                            PolicyRole.valueOf(grant.path("role").asText()),
                            grant.path("farmId").asText(), barns,
                            GrantStatus.valueOf(grant.path("status").asText()),
                            Instant.parse(grant.path("validFrom").asText()),
                            grant.path("validUntil").isNull() ? null : Instant.parse(grant.path("validUntil").asText()),
                            grant.path("version").asLong(-1));
                    if (!references.add(parsed.reference())) throw new IllegalArgumentException("duplicate grant reference");
                    result.add(parsed);
                }
                matchedSubject = new PolicySubject(verifiedSubjectId, result);
            }
            return matchedSubject == null ? new PolicySubject(verifiedSubjectId, List.of()) : matchedSubject;
        } catch (Exception exception) {
            throw HealthException.unavailable("GRANT_FIXTURE_UNAVAILABLE");
        }
    }
}
