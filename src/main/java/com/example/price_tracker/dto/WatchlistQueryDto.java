package com.example.price_tracker.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class WatchlistQueryDto {

    @Min(value = 1, message = "pageNum must be greater than or equal to 1")
    private Long pageNum = 1L;

    @Min(value = 1, message = "pageSize must be greater than or equal to 1")
    private Long pageSize = 10L;
}
