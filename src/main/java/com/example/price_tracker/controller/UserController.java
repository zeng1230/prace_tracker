package com.example.price_tracker.controller;

import com.example.price_tracker.common.Result;
import com.example.price_tracker.service.UserService;
import com.example.price_tracker.vo.UserVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public Result<UserVo> me() {
        return Result.success(userService.getCurrentUser());
    }
}
