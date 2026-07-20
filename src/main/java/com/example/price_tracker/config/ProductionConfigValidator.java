package com.example.price_tracker.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ProductionConfigValidator implements InitializingBean {

    private static final String DEFAULT_JWT_SECRET = "change-me-to-a-secure-secret-key-123456";
    private static final int MIN_WEBHOOK_SECRET_LENGTH = 32;
    private static final Set<String> WEBHOOK_SECRET_PLACEHOLDERS = Set.of(
            "change-me-to-a-secure-webhook-secret",
            "replace-me-with-a-secure-webhook-secret",
            "<random-secret-with-at-least-32-characters>");
    private static final Set<String> HTTP_ALLOWED_PROFILES = Set.of("dev", "local", "test", "it");

    private final Environment environment;
    private final JwtProperties jwtProperties;
    private final NotificationProperties notificationProperties;

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        if (isProd()) {
            requireStrongJwtSecret();
            requireNonBlankProperty("spring.datasource.password");
            requireNonBlankProperty("spring.rabbitmq.password");
        }
        validateWebhookWhenEnabled();
    }

    private boolean isProd() {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }

    private void requireStrongJwtSecret() {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.isBlank() || DEFAULT_JWT_SECRET.equals(secret) || secret.length() < 32) {
            throw new IllegalStateException("prod profile requires a non-default jwt.secret with at least 32 characters");
        }
    }

    private void requireNonBlankProperty(String propertyName) {
        String value = environment.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("prod profile requires non-blank " + propertyName);
        }
    }

    private void validateWebhookWhenEnabled() {
        NotificationProperties.Webhook webhook = notificationProperties.getWebhook();
        if (!webhook.isEnabled()) {
            return;
        }

        URI uri = requireValidWebhookUri(webhook.getUrl());
        requireAllowedWebhookScheme(uri, webhook.isAllowHttp());
        requireStrongWebhookSecret(webhook.getSecret());
        requireRange("notification.webhook.connect-timeout-ms", webhook.resolveConnectTimeoutMs(), 100L, 10_000L);
        requireRange("notification.webhook.read-timeout-ms", webhook.resolveReadTimeoutMs(), 100L, 30_000L);

        NotificationProperties.Delivery delivery = notificationProperties.getDelivery();
        requireRange("notification.delivery.max-attempts", delivery.getMaxAttempts(), 1L, 10L);
        requireRange("notification.delivery.initial-backoff-seconds",
                delivery.getInitialBackoffSeconds(), 1L, 300L);
        requireRange("notification.delivery.max-backoff-seconds",
                delivery.getMaxBackoffSeconds(), 1L, 3600L);
        if (delivery.getInitialBackoffSeconds() > delivery.getMaxBackoffSeconds()) {
            throw new IllegalStateException(
                    "notification.delivery.initial-backoff-seconds must not exceed notification.delivery.max-backoff-seconds");
        }
    }

    private URI requireValidWebhookUri(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("webhook is enabled but notification.webhook.url is blank");
        }
        try {
            URI uri = new URI(value);
            int port = uri.getPort();
            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || (port != -1 && (port < 1 || port > 65_535))) {
                throw invalidWebhookUrl();
            }
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw invalidWebhookUrl();
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw invalidWebhookUrl();
        }
    }

    private void requireAllowedWebhookScheme(URI uri, boolean allowHttp) {
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        if (isProd()) {
            throw new IllegalStateException("prod profile requires HTTPS notification.webhook.url");
        }
        if (!allowHttp || !hasHttpAllowedProfile()) {
            throw new IllegalStateException(
                    "HTTP notification.webhook.url requires dev, local, test, or it profile with notification.webhook.allow-http=true");
        }
    }

    private void requireStrongWebhookSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("notification.webhook.secret must be non-blank when webhook is enabled");
        }
        if (!secret.equals(secret.strip())) {
            throw new IllegalStateException("notification.webhook.secret must not contain leading or trailing whitespace");
        }
        if (secret.length() < MIN_WEBHOOK_SECRET_LENGTH) {
            throw new IllegalStateException("notification.webhook.secret must contain at least 32 characters");
        }
        if (WEBHOOK_SECRET_PLACEHOLDERS.stream().anyMatch(value -> value.equalsIgnoreCase(secret))) {
            throw new IllegalStateException("notification.webhook.secret must not use a documented placeholder value");
        }
    }

    private void requireRange(String propertyName, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalStateException(
                    propertyName + " must be between " + minimum + " and " + maximum);
        }
    }

    private boolean hasHttpAllowedProfile() {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(HTTP_ALLOWED_PROFILES::contains);
    }

    private IllegalStateException invalidWebhookUrl() {
        return new IllegalStateException(
                "notification.webhook.url must be an absolute HTTP(S) URL with a host and without userinfo or fragment");
    }
}
