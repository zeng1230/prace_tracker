package com.example.price_tracker.actuator;

import actuatortest.ActuatorHttpTestApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("prod")
@SpringBootTest(
        classes = ActuatorHttpTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.banner-mode=off",
                "logging.level.root=WARN"
        }
)
class ProdActuatorExposureTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthAndDockerProbePathsRemainPublic() {
        assertPublicHealthEndpoint("/actuator/health");
        assertPublicHealthEndpoint("/actuator/health/liveness");
        assertPublicHealthEndpoint("/actuator/health/readiness");
    }

    @Test
    void anonymousHealthDoesNotLeakComponentsOrInternalDetails() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody())
                .doesNotContain("components", "details", "databaseUrl", "internal-db",
                        "redisHost", "internal-redis", "rabbitmqHost", "internal-rabbitmq");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "env", "configprops", "beans", "mappings", "heapdump", "threaddump",
            "loggers", "shutdown", "caches", "scheduledtasks", "startup", "prometheus"
    })
    void sensitiveAndNonOptedInEndpointsAreNotExposed(String endpoint) {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/" + endpoint, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void assertPublicHealthEndpoint(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
