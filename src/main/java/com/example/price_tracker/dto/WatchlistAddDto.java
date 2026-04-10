package com.example.price_tracker.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class WatchlistAddDto {

    @NotNull(message = "productId must not be null")
    private Long productId;

    @NotNull(message = "targetPrice must not be null")
    @DecimalMin(value = "0.0", inclusive = false, message = "targetPrice must be greater than 0")
    private BigDecimal targetPrice;

    @NotNull(message = "notifyEnabled must not be null")
    private Integer notifyEnabled;
}
