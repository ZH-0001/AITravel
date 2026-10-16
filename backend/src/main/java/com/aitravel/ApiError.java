package com.aitravel;

import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;

public class ApiError extends RuntimeException {
    public final int status;
    public final String code;
    public ApiError(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public static ApiError notFound() { return new ApiError(404, "NOT_FOUND", "内容不存在或无权访问"); }
    public static ApiError bad(String message) { return new ApiError(400, "INVALID_INPUT", message); }
    public static Map<String, Object> body(String code, String message) {
        return Map.of("code", code, "message", message, "traceId", UUID.randomUUID().toString());
    }
}

@RestControllerAdvice
class ErrorAdvice {
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> known(ApiError error) { return ResponseEntity.status(error.status).body(ApiError.body(error.code, error.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception error) { return ResponseEntity.badRequest().body(ApiError.body("INVALID_INPUT", "请求格式或字段不符合要求")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unknown(Exception error) {
        var body = ApiError.body("INTERNAL_ERROR", "系统暂时无法处理，请稍后重试");
        // Avoid logging provider payloads, user conditions or credentials.
        LoggerFactory.getLogger(ErrorAdvice.class).error("Request {} failed: {}", body.get("traceId"), error.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(body);
    }
}
