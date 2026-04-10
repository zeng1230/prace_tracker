package com.example.price_tracker.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.price_tracker.common.PageResult;
import com.example.price_tracker.common.ResultCode;
import com.example.price_tracker.dto.PriceHistoryQueryDto;
import com.example.price_tracker.entity.PriceHistory;
import com.example.price_tracker.entity.Product;
import com.example.price_tracker.exception.BusinessException;
import com.example.price_tracker.mapper.PriceHistoryMapper;
import com.example.price_tracker.mapper.ProductMapper;
import com.example.price_tracker.service.PriceHistoryService;
import com.example.price_tracker.vo.PriceHistoryVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery;

@Service
@RequiredArgsConstructor
public class PriceHistoryServiceImpl implements PriceHistoryService {

    private final PriceHistoryMapper priceHistoryMapper;
    private final ProductMapper productMapper;

    @Override
    public PageResult<PriceHistoryVo> pageByProductId(Long productId, PriceHistoryQueryDto queryDto) {
        Product product = productMapper.selectById(productId);
        if (product == null || product.getStatus() == null || product.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND, "product not found");
        }
        Page<PriceHistory> page = priceHistoryMapper.selectPage(
                new Page<>(queryDto.getPageNum(), queryDto.getPageSize()),
                lambdaQuery(PriceHistory.class)
                        .eq(PriceHistory::getProductId, productId)
                        .orderByDesc(PriceHistory::getCapturedAt)
        );
        List<PriceHistoryVo> records = page.getRecords().stream()
                .map(this::toVo)
                .toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    private PriceHistoryVo toVo(PriceHistory priceHistory) {
        return PriceHistoryVo.builder()
                .id(priceHistory.getId())
                .productId(priceHistory.getProductId())
                .oldPrice(priceHistory.getOldPrice())
                .newPrice(priceHistory.getNewPrice())
                .capturedAt(priceHistory.getCapturedAt())
                .source(priceHistory.getSource())
                .build();
    }
}
