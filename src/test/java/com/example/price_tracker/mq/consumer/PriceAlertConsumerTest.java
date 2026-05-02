package com.example.price_tracker.mq.consumer;

import com.example.price_tracker.mq.message.PriceAlertMessage;
import com.example.price_tracker.redis.RedisCacheService;
import com.example.price_tracker.redis.RedisKeyManager;
import com.example.price_tracker.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceAlertConsumerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private RedisCacheService cacheService;

    @InjectMocks
    private PriceAlertConsumer priceAlertConsumer;

    @Test
    void consumeDelegatesToNotificationService() {
        PriceAlertMessage message = priceAlertMessage();
        when(cacheService.setIfAbsent(idempotentKey(), "1", Duration.ofMinutes(30))).thenReturn(true);

        priceAlertConsumer.consume(message);

        verify(notificationService).consumePriceAlert(message);
    }

    @Test
    void consumeSkipsDuplicateMessageWhenIdempotentKeyAlreadyExists() {
        PriceAlertMessage message = priceAlertMessage();
        when(cacheService.setIfAbsent(idempotentKey(), "1", Duration.ofMinutes(30))).thenReturn(true, false);

        priceAlertConsumer.consume(message);
        priceAlertConsumer.consume(message);

        verify(notificationService).consumePriceAlert(message);
    }

    @Test
    void consumeAcksBySkippingWhenIdempotentKeyIsHit() {
        PriceAlertMessage message = priceAlertMessage();
        when(cacheService.setIfAbsent(idempotentKey(), "1", Duration.ofMinutes(30))).thenReturn(false);

        assertDoesNotThrow(() -> priceAlertConsumer.consume(message));

        verify(notificationService, never()).consumePriceAlert(message);
    }

    @Test
    void consumeLogsErrorAndDoesNotRethrowWhenNotificationHandlingFails() {
        PriceAlertMessage message = priceAlertMessage();
        when(cacheService.setIfAbsent(idempotentKey(), "1", Duration.ofMinutes(30))).thenReturn(true);
        doThrow(new IllegalStateException("boom")).when(notificationService).consumePriceAlert(message);

        assertDoesNotThrow(() -> priceAlertConsumer.consume(message));
    }

    private PriceAlertMessage priceAlertMessage() {
        return PriceAlertMessage.builder()
                .messageId("msg-001")
                .userId(99L)
                .productId(1L)
                .watchlistId(5L)
                .productName("Laptop")
                .currentPrice(new BigDecimal("79.00"))
                .targetPrice(new BigDecimal("80.00"))
                .build();
    }

    private String idempotentKey() {
        return RedisKeyManager.notificationIdempotentKey("mq:msg-001");
    }
}
