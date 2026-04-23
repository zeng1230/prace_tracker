package com.example.price_tracker.mq.producer;

import com.example.price_tracker.config.RabbitMQConfig;
import com.example.price_tracker.mq.message.PriceAlertMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceAlertProducer {

    private final RabbitTemplate rabbitTemplate;

    public void send(PriceAlertMessage message) {
        log.info(
                "Publishing price alert message, exchange={}, routingKey={}, watchlistId={}, productId={}, userId={}, currentPrice={}, targetPrice={}",
                RabbitMQConfig.PRICE_ALERT_EXCHANGE,
                RabbitMQConfig.PRICE_ALERT_ROUTING_KEY,
                message.getWatchlistId(),
                message.getProductId(),
                message.getUserId(),
                message.getCurrentPrice(),
                message.getTargetPrice()
        );
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.PRICE_ALERT_EXCHANGE,
                RabbitMQConfig.PRICE_ALERT_ROUTING_KEY,
                message
        );
        log.info(
                "Published price alert message successfully, watchlistId={}, productId={}, userId={}, currentPrice={}",
                message.getWatchlistId(),
                message.getProductId(),
                message.getUserId(),
                message.getCurrentPrice()
        );
    }
}
