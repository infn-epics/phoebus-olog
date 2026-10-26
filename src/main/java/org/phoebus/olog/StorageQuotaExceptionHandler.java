package org.phoebus.olog;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class StorageQuotaExceptionHandler {
    @ExceptionHandler(StorageQuotaGuard.QuotaExceeded.class)
    public ResponseEntity<Map<String, String>> quotaExceeded(StorageQuotaGuard.QuotaExceeded exception) {
        return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE).body(Map.of(
                "code", "STORAGE_QUOTA_EXCEEDED", "message", exception.getReason()));
    }
}
