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

@ActiveProfiles({"prod", "prod-prometheus"})
@SpringBootTest(
        classes = ActuatorHttpTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.banner-mode=off",
                "logging.level.root=WARN"
        }
)
class ProdPrometheusActuatorExposureTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void prometheusIsAvailableOnlyAfterExplicitProfileOptIn() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("# HELP", "# TYPE");
    }

    @Test
    void prometheusOptInDoesNotExposeSensitiveEndpoints() {
        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/actuator/loggers", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
