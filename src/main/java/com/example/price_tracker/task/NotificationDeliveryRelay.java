package com.example.price_tracker.task;

import com.example.price_tracker.config.NotificationProperties;
import com.example.price_tracker.entity.NotificationDelivery;
import com.example.price_tracker.entity.NotificationDeliveryStatus;
import com.example.price_tracker.mapper.NotificationDeliveryMapper;
import com.example.price_tracker.metrics.PriceTrackerMetrics;
import com.example.price_tracker.notification.WebhookDeliveryClient;
import com.example.price_tracker.notification.WebhookDeliveryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryRelay {

    private static final List<NotificationDeliveryStatus> READY_STATUSES = List.of(
            NotificationDeliveryStatus.PENDING,
            NotificationDeliveryStatus.FAILED_RETRYABLE);

    private final NotificationDeliveryMapper notificationDeliveryMapper;
    private final WebhookDeliveryClient webhookDeliveryClient;
    private final PriceTrackerMetrics metrics;
    private final NotificationProperties notificationProperties;

    private String relayInstanceId = "notification-delivery-relay-" + UUID.randomUUID();

    @Scheduled(fixedDelayString = "#{@notificationProperties.delivery.fixedDelayMs}")
    public void relayScheduledDeliveries() {
        if (!deliveryProperties().isEnabled() || !webhookProperties().isEnabled()) {
            return;
        }
        relayPendingDeliveries();
    }

    public void relayPendingDeliveries() {
        if (!deliveryProperties().isEnabled() || !webhookProperties().isEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int batchLimit = resolveBatchSize();
        int claimed = notificationDeliveryMapper.claimReadyDeliveries(
                READY_STATUSES,
                now,
                batchLimit,
                relayInstanceId,
                now.plusSeconds(resolveClaimLeaseSeconds()));
        if (claimed <= 0) {
            return;
        }
        List<NotificationDelivery> deliveries = notificationDeliveryMapper.selectClaimedReadyDeliveries(
                relayInstanceId,
                LocalDateTime.now(),
                batchLimit);
        for (NotificationDelivery delivery : deliveries) {
            relayOne(delivery);
        }
    }

    private void relayOne(NotificationDelivery delivery) {
        WebhookDeliveryResult result;
        try {
            result = webhookDeliveryClient.send(delivery);
        } catch (RuntimeException exception) {
            markFailure(delivery, exception.getClass().getSimpleName() + ": " + exception.getMessage(), true);
            return;
        }

        if (result.success()) {
            int updated = notificationDeliveryMapper.markSent(delivery.getId(), relayInstanceId, LocalDateTime.now());
            if (updated == 0) {
                logOwnershipLost(delivery, "markSent");
                return;
            }
            metrics.recordNotificationDelivery(PriceTrackerMetrics.RESULT_SUCCESS);
            log.info("notification delivery sent, id={}, eventKey={}, channel={}",
                    delivery.getId(), delivery.getEventKey(), delivery.getChannel());
            return;
        }
        markFailure(delivery, result.error(), result.retryable());
    }

    private void markFailure(NotificationDelivery delivery, String error, boolean retryable) {
        int nextAttempts = normalizeAttempts(delivery) + 1;
        if (!retryable || nextAttempts >= deliveryProperties().getMaxAttempts()) {
            int updated = notificationDeliveryMapper.markDead(
                    delivery.getId(), relayInstanceId, nextAttempts, truncate(error), LocalDateTime.now());
            if (updated == 0) {
                logOwnershipLost(delivery, "markDead");
                return;
            }
            metrics.recordNotificationDelivery("dead");
            log.warn("notification delivery marked dead, id={}, eventKey={}, attempts={}, error={}",
                    delivery.getId(), delivery.getEventKey(), nextAttempts, error);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRetryAt = now.plusSeconds(backoffSeconds(nextAttempts));
        int updated = notificationDeliveryMapper.markRetryable(
                delivery.getId(), relayInstanceId, nextAttempts, nextRetryAt, truncate(error), now);
        if (updated == 0) {
            logOwnershipLost(delivery, "markRetryable");
            return;
        }
        metrics.recordNotificationDelivery(PriceTrackerMetrics.RESULT_FAILED);
        log.warn("notification delivery failed, id={}, eventKey={}, attempts={}, nextRetryAt={}, error={}",
                delivery.getId(), delivery.getEventKey(), nextAttempts, nextRetryAt, error);
    }

    private void logOwnershipLost(NotificationDelivery delivery, String operation) {
        log.warn("notification delivery ownership lost before state update, operation={}, id={}, eventKey={}, claimOwner={}",
                operation, delivery.getId(), delivery.getEventKey(), relayInstanceId);
    }

    private int resolveBatchSize() {
        int batchSize = deliveryProperties().getBatchSize();
        return batchSize > 0 ? batchSize : 20;
    }

    private long resolveClaimLeaseSeconds() {
        long claimLeaseSeconds = deliveryProperties().getClaimLeaseSeconds();
        return claimLeaseSeconds > 0 ? claimLeaseSeconds : 120;
    }

    private int normalizeAttempts(NotificationDelivery delivery) {
        return delivery.getAttempts() == null ? 0 : delivery.getAttempts();
    }

    private long backoffSeconds(int attempts) {
        long multiplier = 1L << Math.max(0, attempts - 1);
        long backoff = deliveryProperties().getInitialBackoffSeconds() * multiplier;
        return Math.min(backoff, deliveryProperties().getMaxBackoffSeconds());
    }

    private NotificationProperties.Webhook webhookProperties() {
        return notificationProperties.getWebhook();
    }

    private NotificationProperties.Delivery deliveryProperties() {
        return notificationProperties.getDelivery();
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }
}
