package com.example.price_tracker.mq.consumer;

import com.example.price_tracker.config.RabbitMQConfig;
import com.example.price_tracker.mq.message.PriceAlertMessage;
import com.example.price_tracker.redis.RedisCacheService;
import com.example.price_tracker.redis.RedisKeyManager;
import com.example.price_tracker.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceAlertConsumer {

    private final NotificationService notificationService;
    private final RedisCacheService cacheService;

    @Value("${notification.consumer-idempotent.ttl-minutes:30}")
    private long consumerIdempotentTtlMinutes = 30;

    @RabbitListener(queues = RabbitMQConfig.PRICE_ALERT_QUEUE)
    public void consume(PriceAlertMessage message) {
        log.info(
                "Received price alert message from queue={}, messageId={}, watchlistId={}, productId={}, userId={}, currentPrice={}, targetPrice={}",
                RabbitMQConfig.PRICE_ALERT_QUEUE,
                message == null ? null : message.getMessageId(),
                message == null ? null : message.getWatchlistId(),
                message == null ? null : message.getProductId(),
                message == null ? null : message.getUserId(),
                message == null ? null : message.getCurrentPrice(),
                message == null ? null : message.getTargetPrice()
        );
        String idempotentKey = buildIdempotentKey(message);
        boolean acquired = cacheService.setIfAbsent(
                idempotentKey,
                "1",
                Duration.ofMinutes(consumerIdempotentTtlMinutes));
        if (!acquired) {
            log.info(
                    "Idempotent hit for price alert message, key={}, messageId={}, watchlistId={}, productId={}, userId={}, decision=ack_skip",
                    idempotentKey,
                    message == null ? null : message.getMessageId(),
                    message == null ? null : message.getWatchlistId(),
                    message == null ? null : message.getProductId(),
                    message == null ? null : message.getUserId()
            );
            return;
        }
        try {
            log.info(
                    "Start processing price alert message, key={}, messageId={}, watchlistId={}, productId={}, userId={}",
                    idempotentKey,
                    message == null ? null : message.getMessageId(),
                    message == null ? null : message.getWatchlistId(),
                    message == null ? null : message.getProductId(),
                    message == null ? null : message.getUserId()
            );
            notificationService.consumePriceAlert(message);
            log.info(
                    "Notification send success, key={}, messageId={}, watchlistId={}, productId={}, userId={}",
                    idempotentKey,
                    message == null ? null : message.getMessageId(),
                    message == null ? null : message.getWatchlistId(),
                    message == null ? null : message.getProductId(),
                    message == null ? null : message.getUserId()
            );
        } catch (Exception ex) {
            log.error(
                    "Notification send failed, key={}, messageId={}, watchlistId={}, productId={}, userId={}, decision=ack_keep_idempotent_key_until_ttl",
                    idempotentKey,
                    message == null ? null : message.getMessageId(),
                    message == null ? null : message.getWatchlistId(),
                    message == null ? null : message.getProductId(),
                    message == null ? null : message.getUserId(),
                    ex
            );
        }
    }

    private String buildIdempotentKey(PriceAlertMessage message) {
        if (message == null) {
            return RedisKeyManager.notificationIdempotentKey("mq:null");
        }
        if (message.getMessageId() != null && !message.getMessageId().isBlank()) {
            return RedisKeyManager.notificationIdempotentKey("mq:" + message.getMessageId());
        }
        return RedisKeyManager.notificationIdempotentKey(
                "mq:"
                        + message.getUserId()
                        + ":"
                        + message.getProductId()
                        + ":"
                        + message.getTargetPrice()
                        + ":"
                        + message.getCurrentPrice()
                        + ":"
                        + message.getTriggeredAt()
        );
    }
}
