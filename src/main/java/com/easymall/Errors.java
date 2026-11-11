package com.easymall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.security.access.AccessDeniedException;

@RestControllerAdvice
public class Errors {
    private static final Logger log = LoggerFactory.getLogger(Errors.class);
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Api<Void>> business(BusinessException e) { return reply(e.status,e.code,e.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
        HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        MissingRequestHeaderException.class, ConstraintViolationException.class})
    ResponseEntity<Api<Void>> validation(Exception e) { return reply(400,"VALIDATION_ERROR","请求参数无效，请核对 OpenAPI 中的字段及范围"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Api<Void>> integrity(Exception e) { return reply(409,"CONFLICT","唯一值冲突或资源仍被引用"); }
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<Api<Void>> busy(Exception e) { return reply(409,"RETRYABLE_CONFLICT","事务竞争，请使用原幂等键重试"); }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Api<Void>> denied(Exception e) { return reply(403,"FORBIDDEN","无权操作"); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Api<Void>> missing(Exception e) { return reply(404,"NOT_FOUND","接口不存在"); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Api<Void>> method(Exception e) { return reply(405,"METHOD_NOT_ALLOWED","不支持该请求方法"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Api<Void>> unexpected(Exception e) { log.error("Unhandled request failure",e); return reply(500,"INTERNAL_ERROR","服务内部错误"); }
    private ResponseEntity<Api<Void>> reply(int status,String code,String message) { return ResponseEntity.status(status).body(Api.error(code,message)); }
}
