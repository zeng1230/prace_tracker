package com.example.price_tracker.service;

public interface PriceService {

    void refreshProductPrice(Long productId);

    void refreshActiveProducts();
}
