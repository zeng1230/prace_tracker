package com.example.price_tracker.util;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class PriceMockUtil {

    private static final BigDecimal DEFAULT_PRICE = new BigDecimal("100.00");

    public BigDecimal generateNextPrice(BigDecimal currentPrice) {
        BigDecimal basePrice = currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0
                ? DEFAULT_PRICE
                : currentPrice;
        int percent = ThreadLocalRandom.current().nextInt(-15, 16);
        BigDecimal multiplier = BigDecimal.valueOf(100L + percent)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal nextPrice = basePrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        return nextPrice.compareTo(BigDecimal.ONE) < 0 ? BigDecimal.ONE.setScale(2, RoundingMode.HALF_UP) : nextPrice;
    }
}
