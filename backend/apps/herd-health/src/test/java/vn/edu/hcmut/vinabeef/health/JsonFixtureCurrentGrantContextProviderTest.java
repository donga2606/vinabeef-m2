package vn.edu.hcmut.vinabeef.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import vn.edu.hcmut.vinabeef.health.adapter.security.JsonFixtureCurrentGrantContextProvider;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;

class JsonFixtureCurrentGrantContextProviderTest {
    @Test
    void loadsSyntheticCurrentGrantWithoutCaching() {
        JsonFixtureCurrentGrantContextProvider provider = new JsonFixtureCurrentGrantContextProvider(
                new DefaultResourceLoader(), new ObjectMapper(), "classpath:authorization-grants.demo.json");

        var subject = provider.loadCurrent("bs.demo");

        assertEquals("bs.demo", subject.subjectId());
        assertEquals(1, subject.grants().size());
        assertEquals("BARN-DL-01", subject.grants().getFirst().barnIds().iterator().next());
    }

    @Test
    void missingFixtureFailsClosed() {
        JsonFixtureCurrentGrantContextProvider provider = new JsonFixtureCurrentGrantContextProvider(
                new DefaultResourceLoader(), new ObjectMapper(), "file:/no/such/grants.json");

        assertThrows(HealthException.class, () -> provider.loadCurrent("bs.demo"));
    }
}
