package com.example.price_tracker.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import webhookconfigtest.WebhookValidationTestApplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@ExtendWith(OutputCaptureExtension.class)
class WebhookConfigurationStartupTest {

    private static final String STRONG_JWT_SECRET = "1234567890123456789012345678901234567890";
    private static final String STRONG_WEBHOOK_SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void prodStartsWhenWebhookIsDisabled() {
        try (ConfigurableApplicationContext context = start("prod",
                "notification.webhook.enabled=false",
                "notification.webhook.url=",
                "notification.webhook.secret=")) {
            assertThat(context.isActive()).isTrue();
        }
    }

    @Test
    void prodFailsWhenEnabledWebhookUrlIsMissing() {
        Throwable failure = startFailure("prod",
                "notification.webhook.enabled=true",
                "notification.webhook.url=",
                "notification.webhook.secret=" + STRONG_WEBHOOK_SECRET);

        assertRootMessage(failure, "notification.webhook.url");
    }

    @Test
    void prodFailsWhenEnabledWebhookSecretIsMissing() {
        Throwable failure = startFailure("prod",
                "notification.webhook.enabled=true",
                "notification.webhook.url=https://hooks.example.invalid/events",
                "notification.webhook.secret=");

        assertRootMessage(failure, "notification.webhook.secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short-secret",
            "change-me-to-a-secure-webhook-secret",
            "replace-me-with-a-secure-webhook-secret",
            "<random-secret-with-at-least-32-characters>",
            " 0123456789abcdef0123456789abcdef "
    })
    void prodRejectsWeakPlaceholderOrWhitespaceSecrets(String secret) {
        Throwable failure = startFailure("prod",
                "notification.webhook.enabled=true",
                "notification.webhook.url=https://hooks.example.invalid/events",
                "notification.webhook.secret=" + secret);

        assertRootMessage(failure, "notification.webhook.secret");
    }

    @Test
    void prodRejectsHttpEvenWhenExternalConfigurationTriesToAllowIt() {
        Throwable failure = startFailure("prod",
                validWebhookEnabledProperties("http://127.0.0.1:9000/hook",
                        "notification.webhook.allow-http=true"));

        assertRootMessage(failure, "requires HTTPS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "local", "test", "it"})
    void explicitNonProductionProfilesAllowLocalHttp(String profile) {
        try (ConfigurableApplicationContext context = start(profile,
                validWebhookEnabledProperties("http://127.0.0.1:9000/hook"))) {
            assertThat(context.getBean(NotificationProperties.class).getWebhook().isAllowHttp()).isTrue();
        }
    }

    @Test
    void prodAcceptsValidHttpsConfigurationAndDeliveryKillSwitch() {
        try (ConfigurableApplicationContext context = start("prod",
                validWebhookEnabledProperties("https://hooks.example.invalid/events?tenant=price-tracker",
                        "notification.delivery.enabled=false"))) {
            NotificationProperties properties = context.getBean(NotificationProperties.class);
            assertThat(properties.getWebhook().isEnabled()).isTrue();
            assertThat(properties.getDelivery().isEnabled()).isFalse();
        }
    }

    @Test
    void newTimeoutsOverrideLegacyTimeoutAndLegacyRemainsFallback() {
        try (ConfigurableApplicationContext legacyContext = start("prod",
                validWebhookEnabledProperties("https://hooks.example.invalid/events",
                        "notification.webhook.timeout-ms=4000"));
             ConfigurableApplicationContext newContext = start("prod",
                     validWebhookEnabledProperties("https://hooks.example.invalid/events",
                             "notification.webhook.timeout-ms=4000",
                             "notification.webhook.connect-timeout-ms=500",
                             "notification.webhook.read-timeout-ms=600"))) {
            NotificationProperties.Webhook legacy = legacyContext.getBean(NotificationProperties.class).getWebhook();
            NotificationProperties.Webhook current = newContext.getBean(NotificationProperties.class).getWebhook();
            assertThat(legacy.resolveConnectTimeoutMs()).isEqualTo(4000L);
            assertThat(legacy.resolveReadTimeoutMs()).isEqualTo(4000L);
            assertThat(current.resolveConnectTimeoutMs()).isEqualTo(500L);
            assertThat(current.resolveReadTimeoutMs()).isEqualTo(600L);
        }
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidNumericProperties")
    void enabledWebhookRejectsOutOfRangeTimeoutAndRetryConfiguration(
            String propertyName, String value, String expectedMessage) {
        Throwable failure = startFailure("prod",
                validWebhookEnabledProperties("https://hooks.example.invalid/events",
                        propertyName + "=" + value));

        assertRootMessage(failure, expectedMessage);
    }

    @Test
    void enabledWebhookRejectsInitialBackoffGreaterThanMaximum() {
        Throwable failure = startFailure("prod",
                validWebhookEnabledProperties("https://hooks.example.invalid/events",
                        "notification.delivery.initial-backoff-seconds=200",
                        "notification.delivery.max-backoff-seconds=100"));

        assertRootMessage(failure, "must not exceed");
    }

    @Test
    void failureAndLogsDoNotContainRawSecret(CapturedOutput output) {
        String rawSecret = "change-me-to-a-secure-webhook-secret";

        Throwable failure = startFailure("prod",
                "notification.webhook.enabled=true",
                "notification.webhook.url=https://hooks.example.invalid/events?token=must-not-appear",
                "notification.webhook.secret=" + rawSecret);

        assertRootMessage(failure, "notification.webhook.secret");
        assertThat(allMessages(failure)).doesNotContain(rawSecret, "must-not-appear");
        assertThat(output.getAll()).doesNotContain(rawSecret, "must-not-appear");
    }

    private ConfigurableApplicationContext start(String profile, String... properties) {
        SpringApplication application = new SpringApplication(WebhookValidationTestApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        return application.run(arguments(profile, properties));
    }

    private Throwable startFailure(String profile, String... properties) {
        Throwable failure = catchThrowable(() -> start(profile, properties));
        assertThat(failure).isNotNull();
        return failure;
    }

    private String[] arguments(String profile, String... properties) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.profiles.active=" + profile,
                "--spring.main.banner-mode=off",
                "--logging.level.root=OFF",
                "--jwt.secret=" + STRONG_JWT_SECRET,
                "--spring.datasource.password=db-secret",
                "--spring.rabbitmq.password=mq-secret"));
        Arrays.stream(properties).map(value -> "--" + value).forEach(arguments::add);
        return arguments.toArray(String[]::new);
    }

    private String[] validWebhookEnabledProperties(String url, String... additionalProperties) {
        List<String> properties = new ArrayList<>(List.of(
                "notification.webhook.enabled=true",
                "notification.webhook.url=" + url,
                "notification.webhook.secret=" + STRONG_WEBHOOK_SECRET));
        properties.addAll(Arrays.asList(additionalProperties));
        return properties.toArray(String[]::new);
    }

    private void assertRootMessage(Throwable failure, String expectedMessage) {
        assertThat(rootCause(failure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);
    }

    private Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private static Stream<Arguments> invalidNumericProperties() {
        return Stream.of(
                Arguments.of("notification.webhook.connect-timeout-ms", "99", "connect-timeout-ms"),
                Arguments.of("notification.webhook.connect-timeout-ms", "10001", "connect-timeout-ms"),
                Arguments.of("notification.webhook.read-timeout-ms", "99", "read-timeout-ms"),
                Arguments.of("notification.webhook.read-timeout-ms", "30001", "read-timeout-ms"),
                Arguments.of("notification.delivery.max-attempts", "0", "max-attempts"),
                Arguments.of("notification.delivery.max-attempts", "11", "max-attempts"),
                Arguments.of("notification.delivery.initial-backoff-seconds", "0", "initial-backoff-seconds"),
                Arguments.of("notification.delivery.initial-backoff-seconds", "301", "initial-backoff-seconds"),
                Arguments.of("notification.delivery.max-backoff-seconds", "0", "max-backoff-seconds"),
                Arguments.of("notification.delivery.max-backoff-seconds", "3601", "max-backoff-seconds"));
    }
}
