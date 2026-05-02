package com.example.price_tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.price_tracker.common.PageResult;
import com.example.price_tracker.common.ResultCode;
import com.example.price_tracker.context.UserContext;
import com.example.price_tracker.dto.WatchlistAddDto;
import com.example.price_tracker.dto.WatchlistQueryDto;
import com.example.price_tracker.dto.WatchlistUpdateDto;
import com.example.price_tracker.entity.Product;
import com.example.price_tracker.entity.Watchlist;
import com.example.price_tracker.exception.BusinessException;
import com.example.price_tracker.mapper.ProductMapper;
import com.example.price_tracker.mapper.WatchlistMapper;
import com.example.price_tracker.redis.RedisCacheService;
import com.example.price_tracker.redis.RedisKeyManager;
import com.example.price_tracker.service.WatchlistService;
import com.example.price_tracker.vo.WatchlistVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WatchlistServiceImpl implements WatchlistService {

    private static final int ACTIVE_STATUS = 1;
    private static final int INACTIVE_STATUS = 0;
    private static final Duration WATCHLIST_CACHE_BASE_TTL = Duration.ofMinutes(10);
    private static final Duration WATCHLIST_CACHE_TTL_JITTER = Duration.ofMinutes(2);

    private final WatchlistMapper watchlistMapper;
    private final ProductMapper productMapper;
    private final RedisCacheService cacheService;

    @Override
    public Long addWatchlist(WatchlistAddDto watchlistAddDto) {
        Long currentUserId = requireCurrentUserId();
        Product product = getActiveProductOrThrow(watchlistAddDto.getProductId());
        Watchlist existing = watchlistMapper.selectOne(new LambdaQueryWrapper<Watchlist>()
                .eq(Watchlist::getUserId, currentUserId)
                .eq(Watchlist::getProductId, watchlistAddDto.getProductId())
                .last("limit 1"));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            Watchlist watchlist = Watchlist.builder()
                    .userId(currentUserId)
                    .productId(product.getId())
                    .targetPrice(watchlistAddDto.getTargetPrice())
                    .notifyEnabled(normalizeNotifyEnabled(watchlistAddDto.getNotifyEnabled()))
                    .status(ACTIVE_STATUS)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            watchlistMapper.insert(watchlist);
            evictWatchlistCache(currentUserId);
            return watchlist.getId();
        }
        if (isActive(existing.getStatus())) {
            log.info("duplicate watch ignored, userId={}, productId={}, watchlistId={}",
                    currentUserId, watchlistAddDto.getProductId(), existing.getId());
            return existing.getId();
        }
        existing.setTargetPrice(watchlistAddDto.getTargetPrice());
        existing.setNotifyEnabled(normalizeNotifyEnabled(watchlistAddDto.getNotifyEnabled()));
        existing.setLastNotifiedPrice(null);
        existing.setStatus(ACTIVE_STATUS);
        existing.setUpdatedAt(now);
        watchlistMapper.updateById(existing);
        evictWatchlistCache(currentUserId);
        return existing.getId();
    }

    @Override
    public PageResult<WatchlistVo> pageMyWatchlist(WatchlistQueryDto queryDto) {
        Long currentUserId = requireCurrentUserId();
        String cacheKey = RedisKeyManager.userWatchlistKey(currentUserId);
        @SuppressWarnings("unchecked")
        PageResult<WatchlistVo> cached = cacheService.get(cacheKey, PageResult.class);
        if (cached != null) {
            log.info("watch list cache hit, key={}", cacheKey);
            return cached;
        }
        log.info("watch list cache miss, key={}", cacheKey);
        Page<Watchlist> page = watchlistMapper.selectPage(
                new Page<>(queryDto.getPageNum(), queryDto.getPageSize()),
                new LambdaQueryWrapper<Watchlist>()
                        .eq(Watchlist::getUserId, currentUserId)
                        .eq(Watchlist::getStatus, ACTIVE_STATUS)
                        .orderByDesc(Watchlist::getUpdatedAt)
        );
        Map<Long, Product> productMap = buildProductMap(page.getRecords());
        List<WatchlistVo> records = page.getRecords().stream()
                .map(watchlist -> toVo(watchlist, productMap.get(watchlist.getProductId())))
                .toList();
        PageResult<WatchlistVo> pageResult = PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
        cacheService.set(cacheKey, pageResult, cacheService.randomTtl(WATCHLIST_CACHE_BASE_TTL, WATCHLIST_CACHE_TTL_JITTER));
        return pageResult;
    }

    @Override
    public void updateWatchlist(Long id, WatchlistUpdateDto watchlistUpdateDto) {
        Watchlist watchlist = getOwnedActiveWatchlist(id);
        watchlist.setTargetPrice(watchlistUpdateDto.getTargetPrice());
        watchlist.setNotifyEnabled(normalizeNotifyEnabled(watchlistUpdateDto.getNotifyEnabled()));
        watchlist.setLastNotifiedPrice(null);
        watchlist.setUpdatedAt(LocalDateTime.now());
        watchlistMapper.updateById(watchlist);
        evictWatchlistCache(watchlist.getUserId());
    }

    @Override
    public void deleteWatchlist(Long id) {
        Watchlist watchlist = getOwnedActiveWatchlist(id);
        watchlist.setStatus(INACTIVE_STATUS);
        watchlist.setUpdatedAt(LocalDateTime.now());
        watchlistMapper.updateById(watchlist);
        evictWatchlistCache(watchlist.getUserId());
    }

    private Map<Long, Product> buildProductMap(List<Watchlist> watchlists) {
        if (watchlists == null || watchlists.isEmpty()) {
            return Collections.emptyMap();
        }
        return productMapper.selectBatchIds(watchlists.stream().map(Watchlist::getProductId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private WatchlistVo toVo(Watchlist watchlist, Product product) {
        return WatchlistVo.builder()
                .id(watchlist.getId())
                .productId(watchlist.getProductId())
                .productName(product == null ? null : product.getProductName())
                .productUrl(product == null ? null : product.getProductUrl())
                .platform(product == null ? null : product.getPlatform())
                .currentPrice(product == null ? null : product.getCurrentPrice())
                .currency(product == null ? null : product.getCurrency())
                .imageUrl(product == null ? null : product.getImageUrl())
                .targetPrice(watchlist.getTargetPrice())
                .notifyEnabled(watchlist.getNotifyEnabled())
                .lastNotifiedPrice(watchlist.getLastNotifiedPrice())
                .createdAt(watchlist.getCreatedAt())
                .updatedAt(watchlist.getUpdatedAt())
                .build();
    }

    private Watchlist getOwnedActiveWatchlist(Long id) {
        Long currentUserId = requireCurrentUserId();
        Watchlist watchlist = watchlistMapper.selectById(id);
        if (watchlist == null || !currentUserId.equals(watchlist.getUserId()) || !isActive(watchlist.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "watchlist not found");
        }
        return watchlist;
    }

    private Product getActiveProductOrThrow(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || !isActive(product.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "product not found");
        }
        return product;
    }

    private Long requireCurrentUserId() {
        Long currentUserId = UserContext.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "current user is not authenticated");
        }
        return currentUserId;
    }

    private Integer normalizeNotifyEnabled(Integer notifyEnabled) {
        return notifyEnabled != null && notifyEnabled == 0 ? 0 : 1;
    }

    private boolean isActive(Integer status) {
        return status != null && status == ACTIVE_STATUS;
    }

    private void evictWatchlistCache(Long userId) {
        String cacheKey = RedisKeyManager.userWatchlistKey(userId);
        cacheService.delete(cacheKey);
        log.info("watch list cache evict, key={}", cacheKey);
    }
}
