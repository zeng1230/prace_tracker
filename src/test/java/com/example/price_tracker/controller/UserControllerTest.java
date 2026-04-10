package com.example.price_tracker.controller;

import com.example.price_tracker.common.ResultCode;
import com.example.price_tracker.service.UserService;
import com.example.price_tracker.vo.UserVo;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {

    private final UserService userService = mock(UserService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService)).build();

    @Test
    void meReturnsCurrentUserProfile() throws Exception {
        UserVo userVo = UserVo.builder()
                .id(1L)
                .username("alice")
                .email("alice@example.com")
                .nickname("Alice")
                .status(1)
                .build();
        when(userService.getCurrentUser()).thenReturn(userVo);

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.username").value("alice"));
    }
}
