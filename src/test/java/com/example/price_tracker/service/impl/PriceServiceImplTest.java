package com.example.price_tracker.service.impl;

import com.example.price_tracker.entity.PriceHistory;
import com.example.price_tracker.entity.Product;
import com.example.price_tracker.entity.Watchlist;
import com.example.price_tracker.mapper.PriceHistoryMapper;
import com.example.price_tracker.mapper.ProductMapper;
import com.example.price_tracker.mapper.WatchlistMapper;
import com.example.price_tracker.mq.message.PriceAlertMessage;
import com.example.price_tracker.mq.producer.PriceAlertProducer;
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
    private PriceAlertProducer priceAlertProducer;

    @Mock
    private PriceMockUtil priceMockUtil;

    @InjectMocks
    private PriceServiceImpl priceService;

    @Test
    void refreshProductPriceCreatesHistoryAndAlertMessageWhenTargetReached() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        when(priceMockUtil.generateNextPrice(new BigDecimal("100.00"))).thenReturn(new BigDecimal("79.00"));
        when(watchlistMapper.selectList(any())).thenReturn(List.of(activeWatchlistWithoutDedupPrice()));

        priceService.refreshProductPrice(1L);

        verify(productMapper).updateById(argThat(updatedProduct()));
        verify(priceHistoryMapper).insert(any(PriceHistory.class));
        verify(priceAlertProducer).send(argThat(createdPriceAlertMessage()));
    }

    @Test
    void refreshProductPriceRecordsHistoryButDoesNotSendAlertWhenChangedPriceIsAboveTarget() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        when(priceMockUtil.generateNextPrice(new BigDecimal("100.00"))).thenReturn(new BigDecimal("81.00"));
        when(watchlistMapper.selectList(any())).thenReturn(List.of(activeWatchlistWithoutDedupPrice()));

        priceService.refreshProductPrice(1L);

        verify(productMapper).updateById(argThat((Product product) ->
                new BigDecimal("81.00").compareTo(product.getCurrentPrice()) == 0));
        verify(priceHistoryMapper).insert(any(PriceHistory.class));
        verify(priceAlertProducer, never()).send(any(PriceAlertMessage.class));
    }

    private ArgumentMatcher<Product> updatedProduct() {
        return product -> new BigDecimal("79.00").compareTo(product.getCurrentPrice()) == 0
                && product.getLastCheckedAt() != null;
    }

    private ArgumentMatcher<PriceAlertMessage> createdPriceAlertMessage() {
        return message -> message.getUserId().equals(99L)
                && message.getProductId().equals(1L)
                && message.getWatchlistId().equals(5L)
                && "Laptop".equals(message.getProductName())
                && new BigDecimal("79.00").compareTo(message.getCurrentPrice()) == 0
                && new BigDecimal("80.00").compareTo(message.getTargetPrice()) == 0
                && message.getTriggeredAt() != null;
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
}
