package com.example.price_tracker.exception;

import com.example.price_tracker.common.Result;
import com.example.price_tracker.common.ResultCode;
import jakarta.validation.ConstraintViolationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException exception) {
        return Result.failure(resolveResultCode(exception.getCode()), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.failure(ResultCode.VALIDATE_ERROR, message);
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException exception) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.failure(ResultCode.VALIDATE_ERROR, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException exception) {
        return Result.failure(ResultCode.VALIDATE_ERROR, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception exception) {
        return Result.failure(ResultCode.SYSTEM_ERROR, exception.getMessage());
    }

    private ResultCode resolveResultCode(Integer code) {
        if (code == null) {
            return ResultCode.SYSTEM_ERROR;
        }
        for (ResultCode value : ResultCode.values()) {
            if (value.getCode() == code) {
                return value;
            }
        }
        return ResultCode.SYSTEM_ERROR;
    }
}
