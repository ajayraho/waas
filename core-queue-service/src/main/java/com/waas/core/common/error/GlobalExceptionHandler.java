package com.waas.core.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One error shape for the whole API: RFC 9457 Problem Details plus a {@code code} property.
 * Extending {@link ResponseEntityExceptionHandler} gives the same shape to Spring's own errors
 * (bad JSON, validation failures, missing headers).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        pd.setProperty("code", ex.code());
        return pd;
    }

    /**
     * Redis or Postgres unreachable: 503 tells clients "retry later" rather than "you did
     * something wrong". Join is idempotent, so a retry after a 503 is always safe.
     */
    @ExceptionHandler({RedisConnectionFailureException.class, DataAccessResourceFailureException.class})
    public ProblemDetail handleInfraDown(RuntimeException ex) {
        log.error("Dependency unavailable", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "A backing store is unavailable, retry shortly");
        pd.setProperty("code", "DEPENDENCY_UNAVAILABLE");
        return pd;
    }
}
