package com.example.price_tracker.notification;

import com.example.price_tracker.config.NotificationProperties;
import com.example.price_tracker.entity.NotificationDelivery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultWebhookDeliveryClient implements WebhookDeliveryClient {

    private final NotificationProperties notificationProperties;

    @Override
    public WebhookDeliveryResult send(NotificationDelivery delivery) {
        NotificationProperties.Webhook webhook = notificationProperties.getWebhook();
        String webhookUrl = webhook.getUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return WebhookDeliveryResult.dead("webhook url is blank");
        }
        try {
            String payload = delivery.getPayload();
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofMillis(webhook.resolveReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("X-Price-Tracker-Event-Key", delivery.getEventKey())
                    .header("X-Price-Tracker-Signature", signature(payload))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(webhook.resolveConnectTimeoutMs()))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return WebhookDeliveryResult.success(status);
            }
            if (status >= 500 || status == 408 || status == 429) {
                return WebhookDeliveryResult.retryable("webhook retryable status=" + status);
            }
            return WebhookDeliveryResult.dead("webhook client error status=" + status);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return WebhookDeliveryResult.retryable("webhook interrupted");
        } catch (Exception exception) {
            return WebhookDeliveryResult.retryable(
                    "webhook request failed: " + exception.getClass().getSimpleName());
        }
    }

    private String signature(String payload) {
        String webhookSecret = notificationProperties.getWebhook().getSecret();
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            log.warn("webhook signature failed, event omitted, errorType={}",
                    exception.getClass().getSimpleName());
            return "";
        }
    }
}
