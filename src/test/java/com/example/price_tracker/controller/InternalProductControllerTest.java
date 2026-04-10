package com.example.price_tracker.controller;

import com.example.price_tracker.service.PriceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalProductControllerTest {

    private final PriceService priceService = mock(PriceService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalProductController(priceService)).build();

    @Test
    void refreshPriceDelegatesToService() throws Exception {
        mockMvc.perform(post("/api/internal/products/3/refresh-price"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(priceService).refreshProductPrice(3L);
    }
}
