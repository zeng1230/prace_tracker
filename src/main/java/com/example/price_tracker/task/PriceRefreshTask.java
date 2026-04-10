package com.example.price_tracker.task;

import com.example.price_tracker.service.PriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PriceRefreshTask {

    private final PriceService priceService;

    @Scheduled(cron = "${price.refresh.cron:0 0/30 * * * ?}")
    public void refreshActiveProducts() {
        priceService.refreshActiveProducts();
    }
}
