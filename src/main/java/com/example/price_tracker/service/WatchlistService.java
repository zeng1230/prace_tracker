package com.example.price_tracker.service;

import com.example.price_tracker.common.PageResult;
import com.example.price_tracker.dto.WatchlistAddDto;
import com.example.price_tracker.dto.WatchlistQueryDto;
import com.example.price_tracker.dto.WatchlistUpdateDto;
import com.example.price_tracker.vo.WatchlistVo;

public interface WatchlistService {

    Long addWatchlist(WatchlistAddDto watchlistAddDto);

    PageResult<WatchlistVo> pageMyWatchlist(WatchlistQueryDto queryDto);

    void updateWatchlist(Long id, WatchlistUpdateDto watchlistUpdateDto);

    void deleteWatchlist(Long id);
}
