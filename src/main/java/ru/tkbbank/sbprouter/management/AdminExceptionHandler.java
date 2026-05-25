package ru.tkbbank.sbprouter.management;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.tkbbank.sbprouter.management.dto.ErrorBody;

@RestControllerAdvice(basePackages = "ru.tkbbank.sbprouter.management")
public class AdminExceptionHandler {
    @ExceptionHandler(ConfigValidationException.class)
    public ResponseEntity<ErrorBody> onValidation(ConfigValidationException e) {
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR", e.getMessage(), e.getField()));
    }
    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<ErrorBody> onConflict(VersionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("VERSION_CONFLICT", e.getMessage(), null));
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorBody> onOther(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorBody("INTERNAL_ERROR", e.getMessage(), null));
    }
}
