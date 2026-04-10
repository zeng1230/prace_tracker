package com.example.price_tracker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.price_tracker.entity.PriceHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PriceHistoryMapper extends BaseMapper<PriceHistory> {
}
