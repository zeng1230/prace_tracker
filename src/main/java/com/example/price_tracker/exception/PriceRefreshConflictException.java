package com.example.price_tracker.exception;

import java.math.BigDecimal;

public class PriceRefreshConflictException extends RuntimeException {

    public PriceRefreshConflictException(Long productId, BigDecimal expectedPrice, BigDecimal actualPrice) {
        super("product price changed concurrently, productId=" + productId
                + ", expectedPrice=" + expectedPrice
                + ", actualPrice=" + actualPrice);
    }
}
