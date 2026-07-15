package com.example.price_tracker.actuator;

import actuatortest.ActuatorHttpTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("dev")
@SpringBootTest(
        classes = ActuatorHttpTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.banner-mode=off",
                "logging.level.root=WARN"
        }
)
class DevActuatorExposureTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void explicitlyExposesLocalDiagnosticsAndDetailedHealth() {
        ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody())
                .contains("components", "internalDependency", "databaseUrl", "internal-db");
        assertThat(restTemplate.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void sensitiveEndpointsAreStillNotEnabledByTheDevAllowList() {
        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/actuator/heapdump", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
