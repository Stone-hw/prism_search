package com.prismsearch.exception;

import com.prismsearch.common.ApiResponse;
import com.prismsearch.common.BizException;
import com.prismsearch.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Global exception handler translating thrown exceptions into the unified
 * {@link ApiResponse} envelope with the matching HTTP status.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex, HttpServletRequest req) {
        ErrorCode code = ex.getErrorCode() == null ? ErrorCode.INTERNAL_ERROR : ex.getErrorCode();
        log.warn("BizException uri={} code={} msg={}", req.getRequestURI(), code.getCode(), ex.getMessage());
        HttpStatus status = mapStatus(code);
        return ResponseEntity.status(status).body(ApiResponse.fail(code, ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(BindException ex, HttpServletRequest req) {
        String msg = firstFieldError(ex);
        log.info("Validation failed uri={} msg={}", req.getRequestURI(), msg);
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, msg));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex,
                                                              HttpServletRequest req) {
        String msg = ex.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(ErrorCode.PARAM_INVALID.getMsg());
        log.info("Constraint violation uri={} msg={}", req.getRequestURI(), msg);
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, msg));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex, HttpServletRequest req) {
        log.info("Bad request uri={} msg={}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception uri={}", req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }

    private static String firstFieldError(BindException ex) {
        FieldError fe = ex.getBindingResult().getFieldError();
        if (fe == null) {
            return ErrorCode.PARAM_INVALID.getMsg();
        }
        String field = fe.getField();
        String msg = fe.getDefaultMessage() == null ? "" : fe.getDefaultMessage();
        return field == null || field.isEmpty() ? msg : field + " " + msg;
    }

    private static HttpStatus mapStatus(ErrorCode code) {
        return switch (code) {
            case PARAM_INVALID -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case ALL_PROVIDERS_FAILED -> HttpStatus.BAD_GATEWAY;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
