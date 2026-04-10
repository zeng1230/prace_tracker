package com.example.price_tracker.service.impl;

import com.example.price_tracker.context.UserContext;
import com.example.price_tracker.dto.WatchlistAddDto;
import com.example.price_tracker.entity.Product;
import com.example.price_tracker.entity.Watchlist;
import com.example.price_tracker.mapper.ProductMapper;
import com.example.price_tracker.mapper.WatchlistMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceImplTest {

    @Mock
    private WatchlistMapper watchlistMapper;

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private WatchlistServiceImpl watchlistService;

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(99L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void addWatchlistReactivatesExistingDisabledRecord() {
        when(productMapper.selectById(1L)).thenReturn(activeProduct());
        when(watchlistMapper.selectOne(any())).thenReturn(existingDisabledWatchlist());

        Long id = watchlistService.addWatchlist(addDto());

        assertEquals(10L, id);
        verify(watchlistMapper).updateById(argThat(reactivatedWatchlist()));
    }

    private ArgumentMatcher<Watchlist> reactivatedWatchlist() {
        return watchlist -> watchlist.getId().equals(10L)
                && watchlist.getUserId().equals(99L)
                && watchlist.getProductId().equals(1L)
                && watchlist.getStatus() == 1
                && watchlist.getNotifyEnabled() == 1
                && new BigDecimal("88.00").compareTo(watchlist.getTargetPrice()) == 0
                && watchlist.getLastNotifiedPrice() == null;
    }

    private WatchlistAddDto addDto() {
        WatchlistAddDto dto = new WatchlistAddDto();
        dto.setProductId(1L);
        dto.setTargetPrice(new BigDecimal("88.00"));
        dto.setNotifyEnabled(1);
        return dto;
    }

    private Product activeProduct() {
        Product product = new Product();
        product.setId(1L);
        product.setStatus(1);
        product.setCurrentPrice(new BigDecimal("100.00"));
        return product;
    }

    private Watchlist existingDisabledWatchlist() {
        Watchlist watchlist = new Watchlist();
        watchlist.setId(10L);
        watchlist.setUserId(99L);
        watchlist.setProductId(1L);
        watchlist.setStatus(0);
        watchlist.setNotifyEnabled(0);
        watchlist.setTargetPrice(new BigDecimal("120.00"));
        return watchlist;
    }
}
