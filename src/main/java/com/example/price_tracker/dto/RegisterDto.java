package com.example.price_tracker.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterDto {

    @NotBlank(message = "username must not be blank")
    @Size(max = 50, message = "username length must be less than or equal to 50")
    private String username;

    @NotBlank(message = "password must not be blank")
    @Size(min = 6, max = 100, message = "password length must be between 6 and 100")
    private String password;

    @Email(message = "email format is invalid")
    @Size(max = 100, message = "email length must be less than or equal to 100")
    private String email;

    @Size(max = 50, message = "nickname length must be less than or equal to 50")
    private String nickname;
}
