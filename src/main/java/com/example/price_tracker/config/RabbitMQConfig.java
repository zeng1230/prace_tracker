package com.example.price_tracker.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableRabbit
@Configuration
public class RabbitMQConfig {

    public static final String PRICE_ALERT_EXCHANGE = "price.alert.exchange";
    public static final String PRICE_ALERT_QUEUE = "price.alert.queue";
    public static final String PRICE_ALERT_ROUTING_KEY = "price.alert";

    @Bean
    public DirectExchange priceAlertExchange() {
        return new DirectExchange(PRICE_ALERT_EXCHANGE, true, false);
    }

    @Bean
    public Queue priceAlertQueue() {
        return new Queue(PRICE_ALERT_QUEUE, true);
    }

    @Bean
    public Binding priceAlertBinding(Queue priceAlertQueue, DirectExchange priceAlertExchange) {
        return BindingBuilder.bind(priceAlertQueue).to(priceAlertExchange).with(PRICE_ALERT_ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
