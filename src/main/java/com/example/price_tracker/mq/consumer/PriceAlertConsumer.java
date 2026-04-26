package com.example.price_tracker.mq.consumer;

import com.example.price_tracker.config.RabbitMQConfig;
import com.example.price_tracker.mq.message.PriceAlertMessage;
import com.example.price_tracker.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceAlertConsumer {

    private final NotificationService notificationService;

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
        try {
            log.info(
                    "Start processing price alert message, messageId={}, watchlistId={}, productId={}, userId={}",
                    message == null ? null : message.getMessageId(),
                    message == null ? null : message.getWatchlistId(),
                    message == null ? null : message.getProductId(),
                    message == null ? null : message.getUserId()
            );
            notificationService.consumePriceAlert(message);
            log.info(
                    "Processed price alert message successfully, messageId={}, watchlistId={}, productId={}, userId={}",
                    message == null ? null : message.getMessageId(),
                    message == null ? null : message.getWatchlistId(),
                    message == null ? null : message.getProductId(),
                    message == null ? null : message.getUserId()
            );
        } catch (Exception ex) {
            log.error(
                    "Failed to process price alert message, messageId={}, watchlistId={}, productId={}, userId={}",
                    message == null ? null : message.getMessageId(),
                    message == null ? null : message.getWatchlistId(),
                    message == null ? null : message.getProductId(),
                    message == null ? null : message.getUserId(),
                    ex
            );
        }
    }
}
