package com.example.price_tracker.exception;

import com.example.price_tracker.common.ResultCode;

public class ProductRefreshUnavailableException extends BusinessException {

    public ProductRefreshUnavailableException(Long productId) {
        super(ResultCode.NOT_FOUND,
                "product is inactive, deleted, or concurrently changed, productId=" + productId);
    }
}
