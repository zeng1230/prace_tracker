package com.example.price_tracker.controller;

import com.example.price_tracker.common.Result;
import com.example.price_tracker.dto.LoginDto;
import com.example.price_tracker.dto.RegisterDto;
import com.example.price_tracker.service.AuthService;
import com.example.price_tracker.vo.LoginVo;
import com.example.price_tracker.vo.UserVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<UserVo> register(@Valid @RequestBody RegisterDto registerDto) {
        return Result.success(authService.register(registerDto));
    }

    @PostMapping("/login")
    public Result<LoginVo> login(@Valid @RequestBody LoginDto loginDto) {
        return Result.success(authService.login(loginDto));
    }
}
