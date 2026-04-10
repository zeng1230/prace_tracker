package com.example.price_tracker.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultTest {

    @Test
    void successResultCarriesCodeMessageAndData() {
        Result<String> result = Result.success("ok");

        assertEquals(ResultCode.SUCCESS.getCode(), result.getCode());
        assertEquals(ResultCode.SUCCESS.getMessage(), result.getMessage());
        assertEquals("ok", result.getData());
    }

    @Test
    void failureResultUsesExplicitResultCode() {
        Result<Void> result = Result.failure(ResultCode.BAD_REQUEST);

        assertEquals(ResultCode.BAD_REQUEST.getCode(), result.getCode());
        assertEquals(ResultCode.BAD_REQUEST.getMessage(), result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void pageResultStoresPaginationMetadata() {
        PageResult<String> pageResult = PageResult.of(List.of("a", "b"), 2L, 1L, 10L);

        assertEquals(List.of("a", "b"), pageResult.getRecords());
        assertEquals(2L, pageResult.getTotal());
        assertEquals(1L, pageResult.getCurrent());
        assertEquals(10L, pageResult.getSize());
        assertTrue(pageResult.getPages() >= 1L);
    }
}
