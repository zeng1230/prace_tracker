package com.example.price_tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.price_tracker.common.ResultCode;
import com.example.price_tracker.entity.PriceHistory;
import com.example.price_tracker.entity.Product;
import com.example.price_tracker.entity.Watchlist;
import com.example.price_tracker.exception.BusinessException;
import com.example.price_tracker.mapper.PriceHistoryMapper;
import com.example.price_tracker.mapper.ProductMapper;
import com.example.price_tracker.mapper.WatchlistMapper;
import com.example.price_tracker.mq.message.PriceAlertMessage;
import com.example.price_tracker.mq.producer.PriceAlertProducer;
import com.example.price_tracker.service.PriceService;
import com.example.price_tracker.util.PriceMockUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PriceServiceImpl implements PriceService {

    private static final int ACTIVE_STATUS = 1;
    private static final int NOTIFY_ENABLED = 1;
    private static final String MOCK_SOURCE = "mock";
    private static final BigDecimal DEFAULT_PRICE = new BigDecimal("100.00");

    private final ProductMapper productMapper;
    private final PriceHistoryMapper priceHistoryMapper;
    private final WatchlistMapper watchlistMapper;
    private final PriceAlertProducer priceAlertProducer;
    private final PriceMockUtil priceMockUtil;

    @Override
    @Transactional
    public void refreshProductPrice(Long productId) {
        Product product = getActiveProductOrThrow(productId);
        BigDecimal oldPrice = product.getCurrentPrice() == null ? DEFAULT_PRICE : product.getCurrentPrice();
        BigDecimal newPrice = priceMockUtil.generateNextPrice(oldPrice);
        LocalDateTime now = LocalDateTime.now();
        product.setLastCheckedAt(now);
        if (newPrice.compareTo(oldPrice) == 0) {
            productMapper.updateById(product);
            return;
        }
        product.setCurrentPrice(newPrice);
        product.setUpdatedAt(now);
        productMapper.updateById(product);
        priceHistoryMapper.insert(PriceHistory.builder()
                .productId(product.getId())
                .oldPrice(oldPrice)
                .newPrice(newPrice)
                .capturedAt(now)
                .source(MOCK_SOURCE)
                .build());
        List<Watchlist> watchlists = watchlistMapper.selectList(new LambdaQueryWrapper<Watchlist>()
                .eq(Watchlist::getProductId, productId)
                .eq(Watchlist::getStatus, ACTIVE_STATUS)
                .eq(Watchlist::getNotifyEnabled, NOTIFY_ENABLED));
        for (Watchlist watchlist : watchlists) {
            if (shouldNotify(watchlist, newPrice)) {
                priceAlertProducer.send(buildPriceAlertMessage(product, watchlist, newPrice, now));
            }
        }
    }

    @Override
    public void refreshActiveProducts() {
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, ACTIVE_STATUS));
        for (Product product : products) {
            refreshProductPrice(product.getId());
        }
    }

    private boolean shouldNotify(Watchlist watchlist, BigDecimal newPrice) {
        return watchlist.getTargetPrice() != null && newPrice.compareTo(watchlist.getTargetPrice()) <= 0;
    }

    private PriceAlertMessage buildPriceAlertMessage(Product product, Watchlist watchlist, BigDecimal newPrice, LocalDateTime now) {
        return PriceAlertMessage.builder()
                .userId(watchlist.getUserId())
                .productId(product.getId())
                .watchlistId(watchlist.getId())
                .currentPrice(newPrice)
                .targetPrice(watchlist.getTargetPrice())
                .productName(product.getProductName())
                .triggeredAt(now)
                .build();
    }

    private Product getActiveProductOrThrow(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || product.getStatus() == null || product.getStatus() != ACTIVE_STATUS) {
            throw new BusinessException(ResultCode.NOT_FOUND, "product not found");
        }
        return product;
    }
}
