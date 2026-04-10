package com.example.price_tracker.controller;

import com.example.price_tracker.common.PageResult;
import com.example.price_tracker.common.Result;
import com.example.price_tracker.dto.WatchlistAddDto;
import com.example.price_tracker.dto.WatchlistQueryDto;
import com.example.price_tracker.dto.WatchlistUpdateDto;
import com.example.price_tracker.service.WatchlistService;
import com.example.price_tracker.vo.WatchlistVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    @PostMapping
    public Result<Long> add(@Valid @RequestBody WatchlistAddDto watchlistAddDto) {
        return Result.success(watchlistService.addWatchlist(watchlistAddDto));
    }

    @GetMapping("/my")
    public Result<PageResult<WatchlistVo>> my(@Valid WatchlistQueryDto queryDto) {
        return Result.success(watchlistService.pageMyWatchlist(queryDto));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody WatchlistUpdateDto watchlistUpdateDto) {
        watchlistService.updateWatchlist(id, watchlistUpdateDto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        watchlistService.deleteWatchlist(id);
        return Result.success();
    }
}
