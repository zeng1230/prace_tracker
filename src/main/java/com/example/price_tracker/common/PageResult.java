package com.example.price_tracker.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> records;
    private Long total;
    private Long current;
    private Long size;
    private Long pages;

    public static <T> PageResult<T> of(List<T> records, Long total, Long current, Long size) {
        long safeSize = size == null || size <= 0 ? 1L : size;
        long safeTotal = total == null ? 0L : total;
        long calculatedPages = safeTotal == 0 ? 0L : (safeTotal + safeSize - 1) / safeSize;
        return PageResult.<T>builder()
                .records(records == null ? Collections.emptyList() : records)
                .total(safeTotal)
                .current(current == null ? 1L : current)
                .size(safeSize)
                .pages(calculatedPages)
                .build();
    }
}
