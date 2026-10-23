package com.antmillion.config;

import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(KiwoomRateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleKiwoomRateLimitExceeded(KiwoomRateLimitExceededException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Rate limit exceeded");
        body.put("message", "키움증권 API 호출 한도 초과. 잠시 후 다시 시도해주세요.");
        body.put("retryAfter", ex.getRetryAfterSeconds());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }

    // Rate Limit 초과 예외 처리 - 429 Too Many Request 반환, Retry-After 헤더 포함
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(RateLimitExceededException ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());

        Map<String, Object> body = new HashMap<>();
        body.put("error", "Rate limit exceeded");
        body.put("message", "한국투자증권 API 호출 한도 초과. 잠시 후 다시 시도해주세요.");
        body.put("retryAfter", ex.getRetryAfterSeconds());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }
}
