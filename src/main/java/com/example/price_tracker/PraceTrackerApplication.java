package com.example.price_tracker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.example.price_tracker.mapper")
public class PraceTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PraceTrackerApplication.class, args);
    }

}
