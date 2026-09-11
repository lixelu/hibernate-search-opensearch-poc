package com.acme.catalog.api;

import com.acme.catalog.query.opensearch.DeepPagingException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * A window past the configured limit is a bad request, not a server fault: the
     * caller can act on it, and a 500 would page the on-call engineer instead.
     */
    @ExceptionHandler(DeepPagingException.class)
    public ProblemDetail deepPaging(DeepPagingException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Requested page is beyond the deep-paging limit");
        problem.setProperty("requestedFrom", e.getRequestedFrom());
        problem.setProperty("maxFrom", e.getMaxFrom());
        return problem;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail notFound(EntityNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
