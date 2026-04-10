package com.example.price_tracker.exception;

import com.example.price_tracker.common.Result;
import com.example.price_tracker.common.ResultCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void handlesBusinessExceptionAsStructuredFailure() throws Exception {
        mockMvc.perform(post("/test/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("bad request"));
    }

    @Test
    void handlesValidationExceptionAsStructuredFailure() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.VALIDATE_ERROR.getCode()))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Validated
    @RestController
    @RequestMapping("/test")
    static class TestController {

        @PostMapping("/business")
        public Result<Void> business() {
            throw new BusinessException(ResultCode.BAD_REQUEST, "bad request");
        }

        @PostMapping("/validate")
        public Result<Void> validate(@Valid @RequestBody TestRequest request) {
            return Result.success();
        }
    }

    static class TestRequest {

        @NotBlank(message = "name must not be blank")
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
