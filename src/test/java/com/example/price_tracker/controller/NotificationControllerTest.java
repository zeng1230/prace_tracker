package com.example.price_tracker.controller;

import com.example.price_tracker.common.PageResult;
import com.example.price_tracker.service.NotificationService;
import com.example.price_tracker.vo.NotificationVo;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(notificationService)).build();

    @Test
    void myReturnsCurrentUsersNotifications() throws Exception {
        when(notificationService.pageMyNotifications(any())).thenReturn(PageResult.of(List.of(
                NotificationVo.builder().id(1L).productId(2L).content("price dropped").build()
        ), 1L, 1L, 10L));

        mockMvc.perform(get("/api/notifications/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].id").value(1L))
                .andExpect(jsonPath("$.data.records[0].content").value("price dropped"));
    }

    @Test
    void readMarksNotificationAsRead() throws Exception {
        mockMvc.perform(put("/api/notifications/8/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(notificationService).markRead(8L);
    }
}
