package com.example.price_tracker.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI priceTrackerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Price Tracker API")
                .description("Price Tracker backend foundation APIs")
                .version("1.0.0")
                .contact(new Contact().name("Price Tracker")));
    }
}
