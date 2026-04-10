package com.example.price_tracker.service.impl;

import com.example.price_tracker.entity.Notification;
import com.example.price_tracker.entity.PriceHistory;
import com.example.price_tracker.entity.Product;
import com.example.price_tracker.entity.Watchlist;
import com.example.price_tracker.mapper.NotificationMapper;
import com.example.price_tracker.mapper.PriceHistoryMapper;
import com.example.price_tracker.mapper.ProductMapper;
import com.example.price_tracker.mapper.WatchlistMapper;
import com.example.price_tracker.util.PriceMockUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceServiceImplTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private PriceHistoryMapper priceHistoryMapper;

    @Mock
    private WatchlistMapper watchlistMapper;

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private PriceMockUtil priceMockUtil;

    @InjectMocks
    private PriceServiceImpl priceService;

    @Test
    void refreshProductPriceCreatesHistoryAndNotificationWhenTargetReached() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        when(priceMockUtil.generateNextPrice(new BigDecimal("100.00"))).thenReturn(new BigDecimal("79.00"));
        when(watchlistMapper.selectList(any())).thenReturn(List.of(activeWatchlistWithoutDedupPrice()));

        priceService.refreshProductPrice(1L);

        verify(productMapper).updateById(argThat(updatedProduct()));
        verify(priceHistoryMapper).insert(any(PriceHistory.class));
        verify(notificationMapper).insert(argThat(createdNotification()));
        verify(watchlistMapper).updateById(argThat(updatedWatchlistLastNotifiedPrice()));
    }

    @Test
    void refreshProductPriceSkipsDuplicateNotificationForSameTriggeredPrice() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        when(priceMockUtil.generateNextPrice(new BigDecimal("100.00"))).thenReturn(new BigDecimal("79.00"));
        when(watchlistMapper.selectList(any())).thenReturn(List.of(activeWatchlistWithDedupPrice()));

        priceService.refreshProductPrice(1L);

        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    private ArgumentMatcher<Product> updatedProduct() {
        return product -> new BigDecimal("79.00").compareTo(product.getCurrentPrice()) == 0
                && product.getLastCheckedAt() != null;
    }

    private ArgumentMatcher<Notification> createdNotification() {
        return notification -> notification.getUserId().equals(99L)
                && notification.getProductId().equals(1L)
                && notification.getWatchlistId().equals(5L)
                && "TARGET_PRICE_REACHED".equals(notification.getNotifyType())
                && notification.getIsRead() == 0
                && notification.getSendStatus() == 1
                && notification.getSentAt() != null;
    }

    private ArgumentMatcher<Watchlist> updatedWatchlistLastNotifiedPrice() {
        return watchlist -> watchlist.getId().equals(5L)
                && new BigDecimal("79.00").compareTo(watchlist.getLastNotifiedPrice()) == 0;
    }

    private Product activeProduct() {
        Product product = new Product();
        product.setId(1L);
        product.setProductName("Laptop");
        product.setCurrentPrice(new BigDecimal("100.00"));
        product.setCurrency("USD");
        product.setStatus(1);
        return product;
    }

    private Watchlist activeWatchlistWithoutDedupPrice() {
        Watchlist watchlist = new Watchlist();
        watchlist.setId(5L);
        watchlist.setUserId(99L);
        watchlist.setProductId(1L);
        watchlist.setTargetPrice(new BigDecimal("80.00"));
        watchlist.setNotifyEnabled(1);
        watchlist.setStatus(1);
        return watchlist;
    }

    private Watchlist activeWatchlistWithDedupPrice() {
        Watchlist watchlist = activeWatchlistWithoutDedupPrice();
        watchlist.setLastNotifiedPrice(new BigDecimal("79.00"));
        return watchlist;
    }
}
